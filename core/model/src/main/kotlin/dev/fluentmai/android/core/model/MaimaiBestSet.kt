package dev.fluentmai.android.core.model

data class MaimaiRatedScore(
    val score: ScoreRecord,
    val chart: ChartRecord?,
    val rating: Int?,
)

enum class MaimaiRatingBucket {
    OLD,
    CURRENT,
    INELIGIBLE,
}

data class MaimaiBestSet(
    val newBest: List<MaimaiRatedScore>,
    val oldBest: List<MaimaiRatedScore>,
    val ineligible: List<MaimaiRatedScore>,
    val currentVersion: MaimaiCurrentVersion?,
) {
    val all: List<MaimaiRatedScore> = oldBest + newBest
    val rating: Int = all.sumOf { it.rating ?: 0 }
}

fun ChartRecord?.ratingBucket(currentVersion: MaimaiCurrentVersion?): MaimaiRatingBucket {
    val currentVersionId = currentVersion?.majorVersion?.id ?: return MaimaiRatingBucket.INELIGIBLE
    val version = this?.chartVersion?.takeIf { it > 0 } ?: return MaimaiRatingBucket.INELIGIBLE
    currentVersion.chartVersionEndExclusive?.let { end ->
        // Named future-release charts must not enter B15 even if the catalog has
        // not yet published that release's numeric boundary.
        val currentYear = maimaiAnnualVersionYear(currentVersion.majorVersion.name)
        val chartYear = maimaiAnnualVersionYear(this.chartVersionName)
        if (chartYear != null && currentYear != null && chartYear > currentYear) return MaimaiRatingBucket.INELIGIBLE
        return when {
            version < currentVersionId -> MaimaiRatingBucket.OLD
            version < end -> MaimaiRatingBucket.CURRENT
            else -> MaimaiRatingBucket.INELIGIBLE
        }
    }
    val currentMajorVersionId = maimaiVersionReferenceFor(currentVersionId)?.versionId ?: currentVersionId
    val chartMajorVersionId = maimaiVersionReferenceFor(version)?.versionId ?: version
    return when {
        chartMajorVersionId == currentMajorVersionId -> MaimaiRatingBucket.CURRENT
        chartMajorVersionId < currentMajorVersionId -> MaimaiRatingBucket.OLD
        else -> MaimaiRatingBucket.INELIGIBLE
    }
}

fun buildMaimaiBestSet(
    scores: List<MaimaiRatedScore>,
    currentVersion: MaimaiCurrentVersion?,
): MaimaiBestSet {
    val ratedScores = scores.filter { it.rating != null }
    val newBest = ratedScores
        .filter { it.chart.ratingBucket(currentVersion) == MaimaiRatingBucket.CURRENT }
        .sortedWith(maimaiRatedScoreComparator)
        .take(15)
    val oldBest = ratedScores
        .filter { it.chart.ratingBucket(currentVersion) == MaimaiRatingBucket.OLD }
        .sortedWith(maimaiRatedScoreComparator)
        .take(35)
    val ineligible = scores.filter {
        it.rating == null || it.chart.ratingBucket(currentVersion) == MaimaiRatingBucket.INELIGIBLE
    }
    return MaimaiBestSet(
        newBest = newBest,
        oldBest = oldBest,
        ineligible = ineligible,
        currentVersion = currentVersion,
    )
}

val maimaiRatedScoreComparator: Comparator<MaimaiRatedScore> =
    compareByDescending<MaimaiRatedScore> { it.rating ?: -1 }
        .thenByDescending { it.score.achievement }
        .thenByDescending { it.chart?.levelValue ?: -1.0 }
        .thenBy { it.score.title }
        .thenBy { it.score.id }
