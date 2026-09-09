package dev.fluentmai.android.core.model

/**
 * Small, Foundation-free API surface intended for Swift callers.
 *
 * Android continues to use the richer domain types directly. Keeping this bridge primitive-only
 * makes Kotlin/Native interop predictable without moving persistence or import concerns into KMP.
 */
class IosDomainBridge {
    fun calculateRating(levelValue: Double, achievement: Double): Int =
        calculateSingleSongRating(levelValue, achievement).rating

    fun calculateCoefficient(achievement: Double): Double = dxRatingCoefficient(achievement)

    /**
     * Returns the number of TAP GREAT judgements a chart can tolerate while still reaching SSS+.
     * A negative value means that the supplied note data cannot reach 100.5000%.
     */
    fun calculateSssPlusTapGreatTolerance(
        tap: Int,
        hold: Int,
        slide: Int,
        touch: Int,
        breakCount: Int,
    ): Int {
        val notes = MaimaiNoteCounts(tap, hold, slide, touch, breakCount)
        if (notes.maximumAchievement < SSS_PLUS_TARGET_ACHIEVEMENT) return -1
        return calculateMaimaiAchievement(
            notes = notes,
            noteKind = MaimaiNoteKind.TAP,
            judgement = MaimaiJudgement.GREAT,
            occurrences = 0,
            targetAchievement = SSS_PLUS_TARGET_ACHIEVEMENT,
        ).toleratedOccurrences
    }

    fun normalizeSearchTerm(value: String): String = normalizeMaimaiVersionName(value)

    fun calculateAchievement(
        tap: Int,
        hold: Int,
        slide: Int,
        touch: Int,
        breakCount: Int,
        noteKind: String,
        judgement: String,
        occurrences: Int,
        targetAchievement: Double,
    ): IosAchievementResult {
        val calculation = calculateMaimaiAchievement(
            notes = MaimaiNoteCounts(tap, hold, slide, touch, breakCount),
            noteKind = enumValueOrThrow<MaimaiNoteKind>(noteKind, "note kind"),
            judgement = enumValueOrThrow<MaimaiJudgement>(judgement, "judgement"),
            occurrences = occurrences,
            targetAchievement = targetAchievement,
        )
        return IosAchievementResult(
            maximumAchievement = calculation.maximumAchievement,
            lossPerJudgement = calculation.lossPerJudgement,
            resultingAchievement = calculation.resultingAchievement,
            toleratedOccurrences = calculation.toleratedOccurrences,
        )
    }
}

data class IosAchievementResult(
    val maximumAchievement: Double,
    val lossPerJudgement: Double,
    val resultingAchievement: Double,
    val toleratedOccurrences: Int,
)

class IosRatingAnalyzer(private val currentVersionId: Int) {
    private val entries = mutableListOf<IosRatedEntry>()
    private val currentMajorVersionId = maimaiVersionReferenceFor(currentVersionId)?.versionId ?: currentVersionId

    init {
        require(currentVersionId > 0) { "currentVersionId must be positive" }
    }

    fun addScore(
        scoreKey: String,
        levelValue: Double,
        achievement: Double,
        chartVersion: Int,
    ) {
        require(scoreKey.isNotBlank()) { "scoreKey must not be blank" }
        entries += IosRatedEntry(
            scoreKey = scoreKey,
            levelValue = levelValue,
            achievement = achievement,
            chartVersion = chartVersion,
            rating = calculateSingleSongRating(levelValue, achievement).rating,
        )
    }

    fun build(): IosBestSetSnapshot {
        val comparator = compareByDescending<IosRatedEntry> { it.rating }
            .thenByDescending { it.achievement }
            .thenByDescending { it.levelValue }
            .thenBy { it.scoreKey }
        val currentEntries = entries
            .filter { it.majorVersionId() == currentMajorVersionId }
            .sortedWith(comparator)
        val oldEntries = entries
            .filter { entry -> entry.majorVersionId()?.let { it < currentMajorVersionId } == true }
            .sortedWith(comparator)
        val newBest = currentEntries.take(15)
        val oldBest = oldEntries.take(35)
        return IosBestSetSnapshot(
            newBest = newBest,
            oldBest = oldBest,
            ineligibleCount = entries.count { entry ->
                entry.majorVersionId()?.let { it > currentMajorVersionId } != false
            },
            outsideBestCount = currentEntries.size + oldEntries.size - newBest.size - oldBest.size,
            totalRating = (newBest + oldBest).sumOf(IosRatedEntry::rating),
        )
    }
}

private fun IosRatedEntry.majorVersionId(): Int? =
    maimaiVersionReferenceFor(chartVersion)?.versionId

data class IosRatedEntry(
    val scoreKey: String,
    val levelValue: Double,
    val achievement: Double,
    val chartVersion: Int,
    val rating: Int,
)

data class IosBestSetSnapshot(
    val newBest: List<IosRatedEntry>,
    val oldBest: List<IosRatedEntry>,
    val ineligibleCount: Int,
    val outsideBestCount: Int,
    val totalRating: Int,
)

private inline fun <reified T : Enum<T>> enumValueOrThrow(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Unknown $label: $value")
