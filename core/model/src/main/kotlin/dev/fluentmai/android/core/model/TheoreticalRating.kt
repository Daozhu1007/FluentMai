package dev.fluentmai.android.core.model

fun theoreticalBestSet(charts: List<ChartRecord>, versions: List<MaimaiMajorVersion>): MaimaiBestSet {
    val current = resolveCurrentMaimaiVersion(versions, charts)
    val eligible = charts.filter { it.isDisabled != true && it.isLocked != true && it.levelValue?.let { v -> v.isFinite() && v > 0 } == true }
        .distinctBy(ChartIdentity::from)
    return buildMaimaiBestSet(eligible.map { chart ->
        val score = ScoreRecord(ChartIdentity.from(chart).stableKey(), chart.songId, chart.title, chart.songType,
            chart.difficulty, chart.level, chart.levelIndex, 100.5, null, null, null, "theoretical", 0)
        MaimaiRatedScore(score, chart, calculateDxRating(requireNotNull(chart.levelValue), 100.5))
    }, current)
}
