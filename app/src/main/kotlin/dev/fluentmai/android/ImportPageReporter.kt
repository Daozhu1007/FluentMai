package dev.fluentmai.android

import dev.fluentmai.android.core.model.ImportPageState
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import dev.fluentmai.android.core.model.DiagnosticDownloadFailure
import dev.fluentmai.android.core.importer.WahlapAttemptLog
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import kotlinx.coroutines.CancellationException

/** Reports existing sequential requests only; never schedules requests or changes retries. */
internal class ImportPageReporter(private val emit: (ImportProgress) -> Unit) {
    private val failures = mutableMapOf<ImportStage, Int>()
    private var activePage: ImportProgress? = null

    fun requestAttempt(category: WahlapRequestCategory, log: WahlapAttemptLog) {
        val page = activePage ?: return
        if (category != WahlapRequestCategory.SCORE_PAGE || !log.willRetry) return
        val reason = when (log.downloadFailure) {
            DiagnosticDownloadFailure.READ_IDLE_TIMEOUT, DiagnosticDownloadFailure.RESPONSE_START_TIMEOUT -> "读取停滞"
            else -> "页面读取异常"
        }
        emit(page.copy(detail = "$reason，正在重试（${log.attempt + 1}/${log.maxAttempts}）"))
    }
    suspend fun <T> page(stage: ImportStage, index: Int, total: Int, name: String,
        complete: (T) -> Boolean = { true }, block: suspend () -> T): T {
        val started = ImportProgress(stage, "正在读取$name", index, total, failures[stage] ?: 0, name)
        activePage = started
        emit(started)
        try {
            val result = block()
            val success = complete(result)
            if (!success) failures[stage] = (failures[stage] ?: 0) + 1
            emit(started.copy(processedPages = index + 1, failedPages = failures[stage] ?: 0,
                pageState = if (success) ImportPageState.Complete else ImportPageState.Failed,
                detail = if (success) "页面已读取" else "页面未完整读取，详情见导入结果"))
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failures[stage] = (failures[stage] ?: 0) + 1
            emit(started.copy(processedPages = index + 1, failedPages = failures.getValue(stage),
                pageState = ImportPageState.Failed, detail = "页面读取失败，详情见导入结果"))
            throw error
        } finally {
            activePage = null
        }
    }
}
