package dev.fluentmai.android.core.model

data class ChartRecord(
    val songId: Int,
    val title: String,
    val artist: String,
    val genre: String,
    val bpm: Int?,
    val songVersion: Int,
    val songVersionName: String?,
    val chartVersion: Int,
    val chartVersionName: String?,
    val songType: SongType,
    val difficulty: Difficulty,
    val levelIndex: Int,
    val level: String,
    val levelValue: Double?,
    val noteDesigner: String,
    val notes: ChartNotes?,
    val isLocked: Boolean? = null,
    val isDisabled: Boolean? = null,
    val fittedConstant: Double? = null,
    val fittedUpdatedAt: Long? = null,
    val japaneseConstant: Double? = null,
    val japaneseConstantCheckedAt: Long? = null,
    val japaneseConstantSourceModifiedAt: Long? = null,
    val japaneseConstantStatus: String = "尚未同步",
)

/** CN minus JP, rounded to the source precision to avoid negative zero and floating-point noise. */
fun ChartRecord.japaneseConstantGap(): Double? {
    val cn = levelValue?.takeIf { it.isFinite() } ?: return null
    val jp = japaneseConstant?.takeIf { it.isFinite() } ?: return null
    val gap = kotlin.math.round((cn - jp) * 10) / 10
    return if (gap == 0.0) 0.0 else gap
}

enum class ChartAvailability {
    AVAILABLE,
    LOCKED,
    DISABLED,
    UPCOMING,
    UNKNOWN,
}

fun ChartRecord.availability(currentVersion: Int?): ChartAvailability =
    when {
        isDisabled == true -> ChartAvailability.DISABLED
        isLocked == true -> ChartAvailability.LOCKED
        currentVersion != null && currentVersion > 0 && songVersion > currentVersion ->
            ChartAvailability.UPCOMING
        isDisabled == false && isLocked == false &&
            (currentVersion == null || currentVersion <= 0 || songVersion <= currentVersion) ->
            ChartAvailability.AVAILABLE
        else -> ChartAvailability.UNKNOWN
    }

data class ChartNotes(
    val total: Int?,
    val tap: Int?,
    val hold: Int?,
    val slide: Int?,
    val touch: Int?,
    val breakCount: Int?,
)
