package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapMusicDetailTarget
import dev.fluentmai.android.core.importer.WahlapPlayCountParser
import dev.fluentmai.android.core.model.ChartPlayCount
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import dev.fluentmai.android.core.model.ImportPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class PlayCountCaptureResult(val chartCount: Int, val warnings: List<String>)

/** No ranking cutoff. Read each song once, saving all its difficulties immediately.
 * Sequential requests avoid flooding the official site or racing session cookies. */
internal suspend fun captureWahlapPlayCounts(
    targets: List<WahlapMusicDetailTarget>,
    fetch: suspend (WahlapMusicDetailTarget) -> String,
    save: suspend (List<ChartPlayCount>) -> Unit,
    onProgress: (String) -> Unit = {},
    pause: suspend () -> Unit = { delay(250) },
    refreshTarget: (suspend (WahlapMusicDetailTarget) -> WahlapMusicDetailTarget?)? = null,
    onDiagnostic: (String) -> Unit = {},
    recoveryPause: suspend () -> Unit = { delay(1000) },
    onPageProgress: (ImportProgress) -> Unit = {},
): PlayCountCaptureResult {
    val saved = mutableSetOf<Triple<String, SongType, Difficulty>>()
    var completed = 0
    var incomplete = 0
    var consecutiveFailures = 0
    var lastReason = ""
    if (targets.isEmpty()) onPageProgress(ImportProgress(ImportStage.PlayCounts, "没有需要读取的单曲详情页", totalPages = 0, pageState = ImportPageState.Complete))
    for (target in targets) {
        onProgress("正在同步全量 PC：${completed + 1}/${targets.size} 首乐曲，已保存 ${saved.size} 张谱面。请保持软件在前台。")
        val started = ImportProgress(ImportStage.PlayCounts, "全部爬取：请保持软件在前台", completed, targets.size, incomplete,
            "${target.title} · ${target.songType}")
        onPageProgress(started)
        val previousIncomplete = incomplete
        try {
            onDiagnostic("PC ${completed + 1}/${targets.size}：${target.title} / ${target.songType} / ${target.difficulties}；来源难度 ${target.sourceDifficulty}")
            suspend fun read(current: WahlapMusicDetailTarget): List<ChartPlayCount> {
                val html = retryActivityFetch(retryOfficialError = false,
                    onRetryFailure = {
                        onDiagnostic("详情请求重试前异常：${diagnosticException(it)}")
                        onPageProgress(started.copy(detail = "当前页面读取异常，正在重试；进度不重复累计"))
                    }) { fetch(current) }
                onPageProgress(started.copy(pageState = ImportPageState.Parsing, detail = "正在解析本页 PC"))
                return WahlapPlayCountParser.counts(html, current).ifEmpty {
                    throw WahlapActivityFetchException("单曲详情未返回可识别的 PC", canRefreshDetail = true)
                }
            }
            val counts = try { read(target) }
            catch (error: WahlapActivityFetchException) {
                onDiagnostic("首次详情失败：${diagnosticException(error)}")
                if (!error.canRefreshDetail || refreshTarget == null) throw error
                // A replay of the rejected idx is not recovery. Revisit the source
                // list to renew both the session and the song's encrypted idx.
                recoveryPause()
                onPageProgress(started.copy(detail = "正在刷新当前曲目的详情链接并重试"))
                var stage = "重新获取成绩列表与详情链接"
                try {
                    onDiagnostic("开始恢复：$stage")
                    val fresh = retryActivityFetch(onRetryFailure = { onDiagnostic("刷新列表重试前异常：${diagnosticException(it)}") }) { refreshTarget(target) }
                        ?: throw WahlapActivityFetchException("刷新后的成绩列表没有对应详情链接")
                    stage = "读取更新后的详情"
                    onDiagnostic("$stage；链接是否更新=${fresh.url != target.url}")
                    read(fresh)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (recoveryError: Exception) {
                    onDiagnostic("恢复失败（$stage）：${diagnosticException(recoveryError)}")
                    throw WahlapActivityFetchException("首次：${error.reason}；$stage：${activityFailureReason(recoveryError)}").apply {
                        initCause(recoveryError)
                        addSuppressed(error)
                    }
                }
            }
            save(counts)
            onDiagnostic("PC 已保存 ${counts.size} 张")
            saved.addAll(counts.map { Triple(it.title, it.songType, it.difficulty) })
            if (!counts.map { it.difficulty }.containsAll(target.difficulties)) {
                incomplete++
                lastReason = "部分已游玩难度未返回 PC"
            }
            consecutiveFailures = 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            onDiagnostic("本曲失败：${diagnosticException(error)}")
            incomplete++
            consecutiveFailures++
            lastReason = activityFailureReason(error)
        }
        completed++
        onPageProgress(started.copy(processedPages = completed, failedPages = incomplete,
            pageState = if (incomplete > previousIncomplete) ImportPageState.Failed else ImportPageState.Complete,
            detail = "已保存 ${saved.size} 张谱面的 PC"))
        // Don't keep sending hundreds of requests with expired credentials or a failing server.
        if (consecutiveFailures >= 3) break
        if (completed < targets.size) pause()
    }
    val remaining = targets.size - completed
    val warnings = if (incomplete + remaining == 0) emptyList() else listOf(
        "PC 同步不完整：$incomplete 首未完整取得，$remaining 首尚未读取（$lastReason）。已取得的 PC 已保存，其余保留原值；请重新导入补齐。",
    )
    return PlayCountCaptureResult(saved.size, warnings)
}
