package dev.fluentmai.android

import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** In-memory, per-import diagnostics. Never includes raw request headers or page bodies. */
internal class ImportDiagnostics(private val mode: String) {
    private val entries = mutableListOf<String>()
    private val started = Instant.now()
    private var omitted = 0

    @Synchronized fun record(message: String) {
        if (entries.size < 4000) entries += "${Instant.now()} ${sanitizeImportDiagnostic(message)}"
        else omitted++
    }

    @Synchronized fun report(): String = buildString {
        appendLine("FluentMai $APP_VERSION 导入诊断")
        appendLine("诊断版本：PC-20260918-rules-1")
        appendLine("方式：$mode；开始时间：$started")
        appendLine("已移除凭据及链接参数；不包含原始网页或请求头。")
        appendLine(entries.joinToString("\n"))
        if (omitted > 0) appendLine("诊断达到上限，另有 $omitted 项未记录。")
    }
}

internal fun sanitizeImportDiagnostic(text: String): String = PrivacyRedactor().redact(
    text.replace(Regex("(?im)^.*\\b(?:cookie|set-cookie|authorization)\\s*[:=].*$"), "[REDACTED_HEADER]")
        .replace(Regex("(?i)https?://[^\\s<>\"']+"), "[REDACTED_URL]")
        .replace(Regex("(?i)(?:[\"']?\\b(?:idx|_t|userId|token|access_token|refresh_token|code|state)[\"']?)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s;,]+)"), "[REDACTED_SECRET]")
)

internal fun diagnosticException(error: Throwable): String = sanitizeImportDiagnostic(error.stackTraceToString())

internal class ImportDiagnosticException(val details: String, cause: Exception) : Exception(cause.message, cause)

internal suspend fun withImportDiagnostics(mode: String, block: suspend (ImportDiagnostics) -> RealWahlapImportResult): RealWahlapImportResult {
    val diagnostics = ImportDiagnostics(mode)
    try {
        val result = block(diagnostics)
        diagnostics.record("结束：解析 ${result.parsedRecordCount} 条成绩；最近记录 ${result.fetchedPlayRecordCount}；PC ${result.fetchedPlayCountCharts} 张；隔离 ${result.importResult.quarantined}；拒绝 ${result.importResult.rejected}")
        result.failures.forEach { diagnostics.record("成绩 ${it.difficulty}：${it.message}") }
        result.supplementalFailures.forEach { diagnostics.record("补充页 ${it.label}：${it.message}") }
        result.activityWarnings.forEach(diagnostics::record)
        return result.copy(diagnosticDetails = diagnostics.report())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        diagnostics.record("导入终止：${diagnosticException(error)}")
        throw ImportDiagnosticException(diagnostics.report(), error)
    }
}
