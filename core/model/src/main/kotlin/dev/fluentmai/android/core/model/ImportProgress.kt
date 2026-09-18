package dev.fluentmai.android.core.model

import kotlin.math.roundToInt

enum class ImportStage(val label: String) {
    Preparing("登录与加载曲库"), Recent("最近游玩记录"), Scores("完整成绩"),
    Supplemental("补充成绩"), PlayCounts("PC 数同步"), Saving("整理本地数据"),
}

enum class ImportPageState(val label: String) {
    Loading("正在读取"), Parsing("正在解析保存"), Complete("已完成"), Failed("未完整读取"),
}

/** Counts logical pages, not HTTP attempts. Retrying a page never advances the count. */
data class ImportProgress(
    val stage: ImportStage,
    val detail: String,
    val processedPages: Int = 0,
    val totalPages: Int? = null,
    val failedPages: Int = 0,
    val pageName: String? = null,
    val pageState: ImportPageState = ImportPageState.Loading,
) {
    val fraction: Float? get() = totalPages?.takeIf { it > 0 }?.let { (processedPages.toFloat() / it).coerceIn(0f, 1f) }
    val percentageLabel: String? get() = fraction?.let {
        val tenths = (it * 1000).roundToInt()
        if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }
    val countLabel: String get() {
        val unit = if (stage == ImportStage.Saving) "项" else "页"
        return totalPages?.let { "已处理 $processedPages/$it $unit" } ?: "已处理 $processedPages $unit"
    }
}
