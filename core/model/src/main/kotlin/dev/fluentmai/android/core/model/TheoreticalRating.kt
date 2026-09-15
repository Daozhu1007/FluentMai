package dev.fluentmai.android.core.model

fun theoreticalBestSet(charts: List<ChartRecord>, versions: List<MaimaiMajorVersion>): MaimaiBestSet {
    val current = resolveCurrentMaimaiVersion(versions, charts)
    // Unlock requirements do not reduce the theoretical ceiling. Removed charts
    // and future annual releases remain ineligible.
    val eligible = charts.filter { it.isDisabled != true && it.levelValue?.let { v -> v.isFinite() && v > 0 } == true }
        .distinctBy(ChartIdentity::from)
        .sortedWith(compareByDescending<ChartRecord> { it.levelValue }.thenBy { ChartIdentity.from(it).stableKey() })
    fun best(bucket: MaimaiRatingBucket, count: Int) = eligible
        .filter { it.ratingBucket(current) == bucket }.take(count).map { chart ->
        val score = ScoreRecord(ChartIdentity.from(chart).stableKey(), chart.songId, chart.title, chart.songType,
            chart.difficulty, chart.level, chart.levelIndex, 101.0, null, "app", null, "theoretical", 0)
        MaimaiRatedScore(score, chart, calculateDxRating(requireNotNull(chart.levelValue), 101.0))
    }
    return MaimaiBestSet(newBest = best(MaimaiRatingBucket.CURRENT, 15),
        oldBest = best(MaimaiRatingBucket.OLD, 35), ineligible = emptyList(), currentVersion = current)
}
