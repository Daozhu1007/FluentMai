package dev.fluentmai.android.core.model

/** A play event, not a best-score update or an import timestamp. */
data class PlayRecord(
    val id: String,
    val songId: Int?,
    val title: String,
    val songType: SongType,
    val difficulty: Difficulty,
    val playedAt: Long,
    val achievement: Double?,
    val dxScore: Int?,
    val fc: String?,
    val fs: String?,
)

data class ChartPlayCount(val title: String, val songType: SongType, val difficulty: Difficulty, val count: Int,
    val isUpperBound: Boolean = false) {
    fun displayText(): String = if (isUpperBound) "≤$count" else count.toString()
}

fun fittedChartKey(songId: Int, type: SongType, levelIndex: Int) = "$songId:${type.name}:$levelIndex"
