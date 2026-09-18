package dev.fluentmai.android

import dev.fluentmai.android.core.database.FluentMaiRepository
import dev.fluentmai.android.core.importer.MaimaiSongCatalog
import dev.fluentmai.android.core.importer.WahlapActivityParser
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import dev.fluentmai.android.core.model.ImportPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class ActivityCaptureResult(val recordCount: Int, val failedPages: Int, val warnings: List<String>)

/** Reuses the current authenticated import session, without storing raw pages or cookies. */
internal suspend fun captureWahlapActivity(catalog: MaimaiSongCatalog, repository: FluentMaiRepository,
    onDiagnostic: (String) -> Unit = {},
    onPageProgress: (ImportProgress) -> Unit = {},
    fetch: suspend (String) -> String): ActivityCaptureResult {
    val queue = ArrayDeque<String>().apply { add(WahlapActivityParser.RECENT_URL) }
    val visited = mutableSetOf<String>()
    val recordIds = mutableSetOf<String>()
    val warnings = mutableListOf<String>()
    var failedPages = 0
    while (queue.isNotEmpty() && visited.size < 10) {
        val url = queue.removeFirst()
        if (!visited.add(url)) continue
        val started = ImportProgress(ImportStage.Recent, "正在同步最近游玩记录；总页数随读取结果确认",
            processedPages = visited.size - 1, failedPages = failedPages, pageName = "游戏记录第 ${visited.size} 页")
        onPageProgress(started)
        var pageState = ImportPageState.Complete
        var stage = "获取"
        try {
            val html = retryActivityFetch(onRetryFailure = { onDiagnostic("最近记录首次请求异常：${diagnosticException(it)}") }) { fetch(url) }
            stage = "解析"
            onPageProgress(started.copy(pageState = ImportPageState.Parsing, detail = "正在解析并保存本页游玩记录"))
            if (!WahlapActivityParser.isRecordPage(html)) throw WahlapActivityFetchException("返回内容不是游戏记录页")
            val records = WahlapActivityParser.records(html, catalog)
            if (records.isEmpty() && html.contains("playlog_top_container"))
                throw WahlapActivityFetchException("已取得游戏记录页面，但记录格式未能解析")
            stage = "保存"
            repository.savePlayRecords(records)
            recordIds.addAll(records.map { it.id })
            queue.addAll(WahlapActivityParser.pagination(html).filterNot { it in visited || it in queue })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            onDiagnostic("${stage}游戏记录失败：${diagnosticException(error)}")
            failedPages++
            pageState = ImportPageState.Failed
            warnings.add("${stage}游戏记录：${activityFailureReason(error)}")
            android.util.Log.w("WahlapActivity", "Recent play page unavailable; retaining previously saved history")
        }
        onPageProgress(started.copy(processedPages = visited.size,
            totalPages = visited.size.takeIf { queue.isEmpty() || visited.size >= 10 }, failedPages = failedPages,
            pageState = pageState, detail = "已读取 ${recordIds.size} 条最近游玩记录"))
        if (queue.isNotEmpty()) delay(150)
    }
    return ActivityCaptureResult(recordIds.size, failedPages, warnings.distinct())
}

internal suspend fun <T> retryActivityFetch(retryOfficialError: Boolean = true, onRetryFailure: (Exception) -> Unit = {}, block: suspend () -> T): T {
    try { return block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        if (!retryOfficialError && error is WahlapActivityFetchException && error.canRefreshDetail) throw error
        onRetryFailure(error)
        delay(500)
    }
    return block()
}

internal fun activityFailureReason(error: Exception): String {
    if (error is WahlapActivityFetchException) return error.reason
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") } -> "请求超时，请重试"
        causes.any { it is java.net.UnknownHostException } -> "域名解析失败，请检查网络"
        causes.any { it is javax.net.ssl.SSLException } -> "安全连接失败，请检查网络"
        else -> "处理失败（${causes.last().javaClass.simpleName}），请重试"
    }
}
