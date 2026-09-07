package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.buildPlayerRecordCatalog
import dev.fluentmai.android.core.model.maimaiVersionReferenceFor
import dev.fluentmai.android.core.model.sssPlusTapGreatTolerance

internal data class ChartQueryFilters(
    val searchQuery: String = "",
    val levelQuery: String = "",
    val constantMin: Double? = null,
    val constantMax: Double? = null,
    val difficulty: Difficulty? = null,
    val genre: ChartGenreFilter = ChartGenreFilter.All,
    val version: ChartVersionFilter = ChartVersionFilter.All,
    val status: ChartStatusFilter = ChartStatusFilter.All,
    val songType: SongType? = null,
    val achievementMin: Double? = null,
    val achievementMax: Double? = null,
    val rank: AchievementRank? = null,
    val fullCombo: FullComboStatus? = null,
    val fullSync: FullSyncStatus? = null,
    val sort: ChartSort = ChartSort.ConstantDesc,
)

internal data class ChartQueryItem(
    val chart: ChartRecord,
    val score: ScoreRecord?,
)

internal data class ChartQueryResult(
    val items: List<ChartQueryItem> = emptyList(),
    val matchingCount: Int = 0,
    val stats: ChartQueryStats = ChartQueryStats(),
)

internal data class ChartQueryStats(
    val totalCharts: Int = 0,
    val playedCharts: Int = 0,
    val rankCounts: Map<AchievementRank, Int> = emptyMap(),
    val fullComboCounts: Map<FullComboStatus, Int> = emptyMap(),
    val fullSyncCounts: Map<FullSyncStatus, Int> = emptyMap(),
) {
    val unplayedCharts: Int get() = (totalCharts - playedCharts).coerceAtLeast(0)
}

internal class ChartQueryEngine private constructor(
    private val entries: List<IndexedChart>,
    val buildTimings: ChartQueryBuildTimings,
) {
    fun query(
        filters: ChartQueryFilters,
        currentVersion: Int,
        limit: Int = 500,
    ): ChartQueryResult {
        val normalizedQuery = normalizeQuery(filters.searchQuery)
        val designerAliases = designerAliasesFor(normalizedQuery)
        val matched = entries.filter { entry ->
            (filters.difficulty == null || entry.chart.difficulty == filters.difficulty) &&
                filters.genre.matches(entry.normalizedGenre) &&
                filters.version.matches(entry.chart, currentVersion) &&
                entry.chart.matchesLevel(filters.levelQuery) &&
                filters.matchesConstant(entry.chart.levelValue) &&
                entry.matchesSearch(normalizedQuery, designerAliases) &&
                filters.status.matches(entry.score) &&
                (filters.songType == null || entry.chart.songType == filters.songType) &&
                filters.matchesScore(entry.score)
        }
        val items = matched
            .sortedWith(filters.sort.comparator())
            .take(limit)
            .map { entry -> ChartQueryItem(entry.chart, entry.score) }
        return ChartQueryResult(
            items = items,
            matchingCount = matched.size,
            stats = ChartQueryStats(
                totalCharts = matched.size,
                playedCharts = matched.count { it.score != null },
                rankCounts = matched.mapNotNull { entry ->
                    entry.score?.let { AchievementRank.fromAchievement(it.achievement) }
                }.groupingBy { it }.eachCount(),
                fullComboCounts = matched.mapNotNull { FullComboStatus.fromWireValue(it.score?.fc) }
                    .groupingBy { it }.eachCount(),
                fullSyncCounts = matched.mapNotNull { FullSyncStatus.fromWireValue(it.score?.fs) }
                    .groupingBy { it }.eachCount(),
            ),
        )
    }

    companion object {
        fun create(
            charts: List<ChartRecord>,
            scores: List<ScoreRecord>,
            aliases: SongAliasCatalog = SongAliasCatalog.Empty,
        ): ChartQueryEngine {
            val startedAt = System.nanoTime()
            val recordsByIdentity = buildPlayerRecordCatalog(charts, scores).records
                .associateBy { it.identity }
            val recordsReadyAt = System.nanoTime()
            val normalizedSearchTexts = normalizeSearchCorpus(
                charts.map { chart ->
                    listOf(
                        chart.title,
                        chart.artist,
                        chart.genre,
                        chart.noteDesigner,
                        chart.songVersionName.orEmpty(),
                        chart.chartVersionName.orEmpty(),
                        chart.bpm?.toString().orEmpty(),
                        chart.songId.toString(),
                        "${chart.songId}-${chart.songType.name}-${chart.difficulty.name}",
                        chart.difficulty.name,
                        chart.songType.name,
                    ).plus(aliases.aliasesFor(chart.songId))
                        .joinToString(SEARCH_FIELD_SEPARATOR)
                },
            )
            val searchReadyAt = System.nanoTime()
            val entries = charts.mapIndexed { index, chart ->
                val playerRecord = recordsByIdentity[ChartIdentity.from(chart)]
                IndexedChart(
                    chart = chart,
                    score = playerRecord?.score,
                    rating = playerRecord?.rating,
                    sssPlusTolerance = chart.sssPlusTapGreatTolerance(),
                    normalizedGenre = normalizeSearchKey(chart.genre),
                    normalizedTitle = normalizedSearchTexts[index]
                        .substringBefore(SEARCH_FIELD_SEPARATOR),
                    searchableText = normalizedSearchTexts[index],
                )
            }
            val entriesReadyAt = System.nanoTime()
            return ChartQueryEngine(
                entries = entries,
                buildTimings = ChartQueryBuildTimings(
                    recordsMillis = (recordsReadyAt - startedAt).nanosToMillis(),
                    searchMillis = (searchReadyAt - recordsReadyAt).nanosToMillis(),
                    entriesMillis = (entriesReadyAt - searchReadyAt).nanosToMillis(),
                ),
            )
        }
    }
}

internal data class ChartQueryBuildTimings(
    val recordsMillis: Long,
    val searchMillis: Long,
    val entriesMillis: Long,
)

private fun Long.nanosToMillis(): Long = this / 1_000_000L

private fun ChartQueryFilters.matchesConstant(value: Double?): Boolean =
    (constantMin == null || (value != null && value >= constantMin)) &&
        (constantMax == null || (value != null && value <= constantMax))

private fun ChartQueryFilters.matchesScore(score: ScoreRecord?): Boolean {
    if (achievementMin != null && (score == null || score.achievement < achievementMin)) return false
    if (achievementMax != null && (score == null || score.achievement > achievementMax)) return false
    if (rank != null && (score == null || AchievementRank.fromAchievement(score.achievement) != rank)) return false
    if (fullCombo != null && FullComboStatus.fromWireValue(score?.fc) != fullCombo) return false
    if (fullSync != null && FullSyncStatus.fromWireValue(score?.fs) != fullSync) return false
    return true
}

internal data class IndexedChart(
    val chart: ChartRecord,
    val score: ScoreRecord?,
    val rating: Int?,
    val sssPlusTolerance: Int?,
    val normalizedGenre: String,
    val normalizedTitle: String,
    val searchableText: String,
) {
    fun matchesSearch(normalizedQuery: String, designerAliases: Set<String>): Boolean =
        normalizedQuery.isBlank() ||
            searchableText.contains(normalizedQuery) ||
            designerAliases.any(searchableText::contains)
}

private const val SEARCH_FIELD_SEPARATOR = "\u0000"
private const val SEARCH_CHART_SEPARATOR = "\u0001"

private fun normalizeSearchCorpus(texts: List<String>): List<String> {
    if (texts.isEmpty()) return emptyList()
    val normalized = normalizeSimplifiedSearchKey(texts.joinToString(SEARCH_CHART_SEPARATOR))
        .split(SEARCH_CHART_SEPARATOR)
    return if (normalized.size == texts.size) normalized else texts.map(::normalizeQuery)
}

internal enum class ChartStatusFilter(val label: String) {
    All("全部"),
    Played("已游玩"),
    Missing("未游玩");

    fun matches(score: ScoreRecord?): Boolean =
        when (this) {
            All -> true
            Played -> score != null
            Missing -> score == null
        }
}

internal enum class ChartGenreFilter(val label: String) {
    All("全部分区"),
    Maimai("舞萌区"),
    OngekiChunithm("中二/音击区"),
    VocaloidNiconico("VOCALOID & NICONICO"),
    Touhou("东方区"),
    GameVariety("GAME & VARIETY"),
    PopsAnime("POPS & ANIME"),
    Utage("宴会场");

    fun matches(normalizedGenre: String): Boolean =
        when (this) {
            All -> true
            Maimai -> normalizedGenre == "maimai"
            OngekiChunithm -> normalizedGenre.contains("オンゲキ") ||
                normalizedGenre.contains("chunithm") ||
                normalizedGenre.contains("中二") ||
                normalizedGenre.contains("音击")
            VocaloidNiconico -> normalizedGenre.contains("vocaloid") ||
                normalizedGenre.contains("niconico") ||
                normalizedGenre.contains("ボーカロイド")
            Touhou -> normalizedGenre.contains("東方") || normalizedGenre.contains("东方")
            GameVariety -> normalizedGenre.contains("ゲーム") ||
                normalizedGenre.contains("バラエティ") ||
                normalizedGenre.contains("game") ||
                normalizedGenre.contains("variety")
            PopsAnime -> normalizedGenre.contains("pops") ||
                normalizedGenre.contains("アニメ") ||
                normalizedGenre.contains("anime")
            Utage -> normalizedGenre.contains("宴会") || normalizedGenre.contains("utage")
        }
}

internal enum class ChartVersionFilter(val label: String, private val versionId: Int? = null) {
    All("全部版本"),
    Current("当前版本"),
    Dx2026("舞萌DX 2026", 25500),
    Dx2025("舞萌DX 2025", 25000),
    Dx2024("舞萌DX 2024", 24000),
    Dx2023("舞萌DX 2023", 23000),
    Dx2022("舞萌DX 2022", 22000),
    Dx2021("舞萌DX 2021", 21000),
    Dx("舞萌DX", 20000),
    Finale("FiNALE · 輝", 19900),
    MilkPlus("MiLK PLUS · 雪", 19500),
    Milk("MiLK · 白", 19000),
    MurasakiPlus("MURASAKi PLUS · 菫", 18500),
    Murasaki("MURASAKi · 紫", 18000),
    PinkPlus("PiNK PLUS · 櫻", 17000),
    Pink("PiNK · 桃", 16000),
    OrangePlus("ORANGE PLUS · 暁", 15000),
    Orange("ORANGE · 橙", 14000),
    GreenPlus("GreeN PLUS · 檄", 13000),
    Green("GreeN · 超", 12000),
    MaimaiPlus("maimai PLUS", 11000),
    Maimai("maimai · 真", 10000),
    Classic("经典世代");

    fun matches(chart: ChartRecord, currentVersion: Int): Boolean =
        when (this) {
            All -> true
            Current -> currentVersion > 0 && chart.majorVersionId() == currentVersion
            Classic -> chart.majorVersionId()?.let { it < 20000 } == true
            else -> chart.majorVersionId() == versionId
        }
}

private fun ChartRecord.majorVersionId(): Int? =
    maimaiVersionReferenceFor(chartVersion.takeIf { it > 0 } ?: songVersion)?.versionId

internal enum class ChartSort(val label: String) {
    ConstantDesc("定数降序"),
    ConstantAsc("定数升序"),
    ToleranceAsc("容错升序"),
    ToleranceDesc("容错降序"),
    RatingDesc("Rating 降序"),
    SongIdAsc("歌曲 ID"),
    VersionDesc("曲库版本降序"),
    VersionAsc("曲库版本升序"),
    AchievementAsc("成绩升序"),
    AchievementDesc("成绩降序"),
    TitleAsc("曲名升序"),
    TitleDesc("曲名降序");

    fun comparator(): Comparator<IndexedChart> {
        val primary = when (this) {
            ConstantDesc -> compareByDescending<IndexedChart> { it.chart.levelValue ?: -1.0 }
                .thenByDescending { it.chart.levelIndex }
                .thenBy { it.normalizedTitle }
            ConstantAsc -> compareBy<IndexedChart> { it.chart.levelValue ?: 999.0 }
                .thenBy { it.chart.levelIndex }
                .thenBy { it.normalizedTitle }
            ToleranceAsc -> compareBy<IndexedChart> { it.sssPlusTolerance ?: Int.MAX_VALUE }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            ToleranceDesc -> compareByDescending<IndexedChart> { it.sssPlusTolerance ?: Int.MIN_VALUE }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            RatingDesc -> compareByDescending<IndexedChart> { it.rating ?: -1 }
                .thenByDescending { it.score?.achievement ?: -1.0 }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            SongIdAsc -> compareBy<IndexedChart> { it.chart.songId }
                .thenBy { it.chart.songType.ordinal }
                .thenBy { it.chart.levelIndex }
            VersionDesc -> compareByDescending<IndexedChart> { it.chart.chartVersion }
                .thenByDescending { it.chart.songVersion }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            VersionAsc -> compareBy<IndexedChart> { it.chart.chartVersion }
                .thenBy { it.chart.songVersion }
                .thenBy { it.chart.levelValue ?: 999.0 }
            AchievementAsc -> compareBy<IndexedChart> { it.score?.achievement ?: 999.0 }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            AchievementDesc -> compareByDescending<IndexedChart> { it.score?.achievement ?: -1.0 }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            TitleAsc -> compareBy<IndexedChart> { it.normalizedTitle }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
            TitleDesc -> compareByDescending<IndexedChart> { it.normalizedTitle }
                .thenByDescending { it.chart.levelValue ?: -1.0 }
        }
        return primary
            .thenBy { it.chart.songId }
            .thenBy { it.chart.songType.ordinal }
            .thenBy { it.chart.levelIndex }
    }
}

private fun ChartRecord.matchesLevel(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return true
    if (!isValidLevelQuery(trimmed)) return true
    val numeric = trimmed.toDoubleOrNull()
    if (numeric != null && trimmed.contains(".")) {
        return levelValue?.let { kotlin.math.abs(it - numeric) < 0.0001 } == true
    }

    val normalized = normalizeQuery(trimmed)
    val isPlusLevel = normalized.endsWith("+")
    val baseLevel = normalized.removeSuffix("+").toIntOrNull()
    if (baseLevel != null) {
        if (level.equals(trimmed, ignoreCase = true)) return true
        val value = levelValue ?: return false
        return if (isPlusLevel) {
            value >= baseLevel + 0.6 - 0.0001 && value <= baseLevel + 0.9 + 0.0001
        } else {
            value >= baseLevel.toDouble() - 0.0001 && value <= baseLevel + 0.5 + 0.0001
        }
    }

    return level.equals(trimmed, ignoreCase = true)
}

internal fun isValidLevelQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val exactConstant = Regex("^(?:[1-9]|1[0-5])\\.\\d$")
    if (exactConstant.matches(trimmed)) {
        return trimmed.toDoubleOrNull()?.let { it in 1.0..15.0 } == true
    }
    if (trimmed.endsWith('+')) {
        return trimmed.dropLast(1).toIntOrNull()?.let { it in 1..14 } == true
    }
    return trimmed.toIntOrNull()?.let { it in 1..15 } == true
}

private fun designerAliasesFor(query: String): Set<String> =
    when {
        query.contains("沙发太") || query.contains("沙發太") ->
            setOf("サファ太").map(::normalizeQuery).toSet()
        query.contains("哈皮") ->
            setOf("はっぴー").map(::normalizeQuery).toSet()
        query.contains("73") ||
            query.contains("shichimi") ||
            query.contains("シチミ") ||
            query.contains("七味") ->
            setOf(
                "7.3ghz",
                "シチミッピー",
                "シチミヘルツ",
                "しちみへるつ",
                "超七味星人",
            ).map(::normalizeQuery).toSet()
        else -> emptySet()
    }
