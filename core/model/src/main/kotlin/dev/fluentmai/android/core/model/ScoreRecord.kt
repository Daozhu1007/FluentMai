package dev.fluentmai.android.core.model

data class ScoreRecord(
    val id: String,
    val songId: Int? = null,
    val title: String,
    val songType: SongType = SongType.STANDARD,
    val difficulty: Difficulty,
    val level: String,
    val levelIndex: Int,
    val achievement: Double,
    val dxScore: Int?,
    val fc: String?,
    val fs: String?,
    val sourceBatchId: String,
    val importedAt: Long,
    val playCount: Int? = null,
    val observedPlayCount: Int = 0,
    val playCountUpperBound: Int? = null,
)
