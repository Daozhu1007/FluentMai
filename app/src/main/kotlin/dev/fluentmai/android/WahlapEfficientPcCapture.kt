package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.CancellationException

internal fun pcRankingSnapshot(difficulty: Difficulty, ranked: List<ChartPlayCount>, charts: List<ChartRecord>,
    targets: List<WahlapMusicDetailTarget>): List<ChartPlayCount> {
    require(ranked.isNotEmpty() && ranked.all { it.difficulty == difficulty && !it.isUpperBound && it.count >= 0 })
    val threshold = ranked.minOf { it.count }
    val exact = ranked.associateBy { it.title to it.songType }
    val identities = charts.filter { it.difficulty == difficulty }.map { it.title to it.songType } +
        targets.filter { difficulty in it.difficulties }.map { it.title to it.songType } + exact.keys
    return identities.distinct().map { (title, type) ->
        exact[title to type] ?: ChartPlayCount(title, type, difficulty, threshold, isUpperBound = true)
    }
}

/** Only five Mybest ranking pages; never requests individual musicDetail pages. */
internal suspend fun captureEfficientPc(catalog: MaimaiSongCatalog, targets: List<WahlapMusicDetailTarget>,
    fetch: suspend (String) -> String, save: suspend (List<ChartPlayCount>) -> Unit,
    onProgress: (String) -> Unit, onDiagnostic: (String) -> Unit,
    onPageProgress: (ImportProgress) -> Unit = {}): PlayCountCaptureResult {
    var exactCount = 0
    val warnings = mutableListOf<String>()
    val charts = catalog.charts()
    for (difficulty in Difficulty.entries) {
        onProgress("效率优先：正在读取 ${difficulty.name} 的 PC 榜单")
        val started = ImportProgress(ImportStage.PlayCounts, "效率优先：正在读取 PC 榜单",
            difficulty.ordinal, Difficulty.entries.size, warnings.size, "${difficulty.name} PC 榜单")
        onPageProgress(started)
        var state = ImportPageState.Loading
        try {
            val html = retryActivityFetch { fetch(WahlapActivityParser.playCountUrl(difficulty)) }
            onPageProgress(started.copy(pageState = ImportPageState.Parsing, detail = "正在解析并保存本页 PC"))
            val ranked = WahlapActivityParser.playCounts(html, difficulty, catalog)
            check(WahlapActivityParser.isPlayCountPage(html, ranked)) { "返回内容不是 PC 榜单" }
            if (ranked.isEmpty()) {
                if (targets.any { difficulty in it.difficulties })
                    error("榜单为空，无法确定该难度的 PC 上界；保留原有数据")
                onDiagnostic("${difficulty.name} PC 榜单为空；没有可用上界，不生成数值")
                state = ImportPageState.Complete
                continue
            }
            check(ranked.size == WahlapActivityParser.playCountCardCount(html)) { "PC 榜单未完整解析，不能计算上界" }
            val snapshot = pcRankingSnapshot(difficulty, ranked, charts, targets)
            save(snapshot)
            exactCount += ranked.size
            state = ImportPageState.Complete
            onDiagnostic("${difficulty.name} PC：${ranked.size} 张精确值，其他 ${snapshot.size - ranked.size} 张为 ≤${ranked.minOf { it.count }}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            state = ImportPageState.Failed
            onDiagnostic("${difficulty.name} PC 榜单失败：${diagnosticException(error)}")
            warnings += "${difficulty.name} PC 榜单未同步，保留原值：${activityFailureReason(error)}"
        } finally {
            if (state != ImportPageState.Loading) onPageProgress(started.copy(processedPages = difficulty.ordinal + 1,
                failedPages = warnings.size, pageState = state, detail = "效率优先：已同步 $exactCount 张谱面的精确 PC"))
        }
    }
    return PlayCountCaptureResult(exactCount, warnings)
}
