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
)

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
