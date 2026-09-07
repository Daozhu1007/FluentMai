package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.ChartNotes
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongAliasEntry
import dev.fluentmai.android.core.model.SongType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartQueryEngineTest {
    @Test
    fun exactLevelInputValidationAcceptsOnlySupportedModes() {
        listOf("", "13", "13+", "13.3", "15", " 14+ ").forEach { assertTrue(it, isValidLevelQuery(it)) }
        listOf("0", "15+", "13.33", "13.", "abc", "16.0").forEach { assertFalse(it, isValidLevelQuery(it)) }
    }

    @Test
    fun indexedSearchSupportsFieldsAliasesIdsAndPlayedStatus() {
        val target = chart(
            songId = 834,
            title = "PANDORA PARADOXXX",
            artist = "削除",
            designer = "サファ太",
        )
        val other = chart(songId = 1001, title = "Other", artist = "Artist", designer = "Designer")
        val engine = ChartQueryEngine.create(
            charts = listOf(target, other),
            scores = listOf(scoreFor(target)),
        )

        assertEquals(
            listOf(target),
            engine.query(ChartQueryFilters(searchQuery = "沙发太"), currentVersion = 25_500)
                .items.map { it.chart },
        )
        assertEquals(
            listOf(target),
            engine.query(ChartQueryFilters(searchQuery = "834"), currentVersion = 25_500)
                .items.map { it.chart },
        )
        val played = engine.query(
            ChartQueryFilters(status = ChartStatusFilter.Played),
            currentVersion = 25_500,
        )
        assertEquals(1, played.matchingCount)
        assertEquals(target, played.items.single().chart)
        assertTrue(played.items.single().score != null)
    }

    @Test
    fun universalSearchNormalizesSimplifiedAndTraditionalChinese() {
        val traditional = chart(songId = 10, title = "華火職人", levelValue = 13.0)
        val simplified = chart(songId = 11, title = "华火职人", levelValue = 13.0)
        val engine = ChartQueryEngine.create(listOf(traditional, simplified), emptyList())

        val simplifiedQuery = engine.query(ChartQueryFilters(searchQuery = "华火职人"), currentVersion = 25_500)
        val traditionalQuery = engine.query(ChartQueryFilters(searchQuery = "華火職人"), currentVersion = 25_500)

        assertEquals(setOf(10, 11), simplifiedQuery.items.map { it.chart.songId }.toSet())
        assertEquals(setOf(10, 11), traditionalQuery.items.map { it.chart.songId }.toSet())
    }

    @Test
    fun currentVersionFilterAndDeterministicSortStayStable() {
        val currentLow = chart(songId = 2, title = "B", levelValue = 13.0, songVersion = 25_500)
        val currentHigh = chart(songId = 1, title = "A", levelValue = 14.0, songVersion = 25_500)
        val future = chart(songId = 3, title = "Future", levelValue = 15.0, songVersion = 26_000)
        val result = ChartQueryEngine.create(listOf(currentLow, future, currentHigh), emptyList()).query(
            filters = ChartQueryFilters(
                version = ChartVersionFilter.Current,
                sort = ChartSort.ConstantDesc,
            ),
            currentVersion = 25_500,
        )

        assertEquals(2, result.matchingCount)
        assertEquals(listOf(currentHigh, currentLow), result.items.map { it.chart })
    }

    @Test
    fun toleranceSortUsesSssPlusTapGreatCountAndKeepsMissingDataLast() {
        val low = chart(
            songId = 1,
            title = "Low tolerance",
            notes = ChartNotes(total = 51, tap = 50, hold = 0, slide = 0, touch = 0, breakCount = 1),
        )
        val high = chart(
            songId = 2,
            title = "High tolerance",
            notes = ChartNotes(total = 140, tap = 100, hold = 10, slide = 10, touch = 10, breakCount = 10),
        )
        val missing = chart(songId = 3, title = "Missing notes")
        val engine = ChartQueryEngine.create(listOf(high, missing, low), emptyList())

        val ascending = engine.query(ChartQueryFilters(sort = ChartSort.ToleranceAsc), currentVersion = 25_500)
        val descending = engine.query(ChartQueryFilters(sort = ChartSort.ToleranceDesc), currentVersion = 25_500)

        assertEquals(listOf(low, high, missing), ascending.items.map { it.chart })
        assertEquals(listOf(high, low, missing), descending.items.map { it.chart })
        assertEquals("容错升序", ChartSort.ToleranceAsc.label)
        assertEquals("容错降序", ChartSort.ToleranceDesc.label)
    }

    @Test
    fun versionFilterUsesCanonicalMinorVersionBoundaries() {
        val pandora = chart(songId = 834, title = "PANDORA PARADOXXX", songVersion = 19_998)
        val finaleStart = chart(songId = 800, title = "EVERGREEN", songVersion = 19_900)
        val milkPlus = chart(songId = 650, title = "Let's Go Away", songVersion = 19_500)

        val result = ChartQueryEngine.create(listOf(pandora, finaleStart, milkPlus), emptyList()).query(
            filters = ChartQueryFilters(version = ChartVersionFilter.Finale),
            currentVersion = 25_500,
        )

        assertEquals(setOf(800, 834), result.items.map { it.chart.songId }.toSet())
    }

    @Test
    fun aliasAndCompositeIdentitySearchHandleWidthCaseAndPunctuation() {
        val target = chart(songId = 834, title = "きゅうくらりん")
        val engine = ChartQueryEngine.create(
            charts = listOf(target),
            scores = emptyList(),
            aliases = SongAliasCatalog.from(listOf(SongAliasEntry(834, listOf("心跳不止")))),
        )

        assertEquals(1, engine.query(ChartQueryFilters(searchQuery = " 心 跳・不止！ "), 25_500).matchingCount)
        assertEquals(1, engine.query(ChartQueryFilters(searchQuery = "８３４-dx-master"), 25_500).matchingCount)
    }

    @Test
    fun combinesConstantTypeAchievementComboAndSyncFilters() {
        val matching = chart(songId = 1, title = "Match", levelValue = 13.7)
        val low = chart(songId = 2, title = "Low", levelValue = 12.9)
        val engine = ChartQueryEngine.create(
            listOf(matching, low),
            listOf(scoreFor(matching).copy(achievement = 100.0, fc = "ap", fs = "fsd")),
        )

        val result = engine.query(
            ChartQueryFilters(
                constantMin = 13.5,
                constantMax = 13.9,
                songType = SongType.DX,
                achievementMin = 99.5,
                achievementMax = 100.1,
                fullCombo = FullComboStatus.AP,
                fullSync = FullSyncStatus.FSD,
            ),
            currentVersion = 25_500,
        )

        assertEquals(listOf(matching), result.items.map { it.chart })
    }

    @Test
    fun unifiedBrowserProvidesRankFilteringAndCurrentConditionStats() {
        val sssPlus = chart(songId = 1, title = "SSS Plus", levelValue = 13.7)
        val sss = chart(songId = 2, title = "SSS", levelValue = 13.6)
        val unplayed = chart(songId = 3, title = "Unplayed", levelValue = 13.5)
        val engine = ChartQueryEngine.create(
            charts = listOf(sssPlus, sss, unplayed),
            scores = listOf(
                scoreFor(sssPlus).copy(achievement = 100.5, fc = "app", fs = "fsdp"),
                scoreFor(sss).copy(achievement = 100.0, fc = "fc", fs = "fs"),
            ),
        )

        val all = engine.query(ChartQueryFilters(), currentVersion = 25_500)
        assertEquals(3, all.stats.totalCharts)
        assertEquals(2, all.stats.playedCharts)
        assertEquals(1, all.stats.unplayedCharts)
        assertEquals(1, all.stats.rankCounts[AchievementRank.SSS_PLUS])
        assertEquals(1, all.stats.rankCounts[AchievementRank.SSS])

        val exact = engine.query(
            ChartQueryFilters(rank = AchievementRank.SSS, sort = ChartSort.RatingDesc),
            currentVersion = 25_500,
        )
        assertEquals(listOf(sss), exact.items.map { it.chart })
        assertEquals(1, exact.stats.totalCharts)
    }

    private fun chart(
        songId: Int,
        title: String,
        artist: String = "Artist",
        designer: String = "Designer",
        levelValue: Double = 13.5,
        songVersion: Int = 25_500,
        notes: ChartNotes? = null,
    ): ChartRecord =
        ChartRecord(
            songId = songId,
            title = title,
            artist = artist,
            genre = "maimai",
            bpm = 180,
            songVersion = songVersion,
            songVersionName = if (songVersion == 25_500) "舞萌DX 2026" else null,
            chartVersion = songVersion,
            chartVersionName = if (songVersion == 25_500) "舞萌DX 2026" else null,
            songType = SongType.DX,
            difficulty = Difficulty.MASTER,
            levelIndex = Difficulty.MASTER.levelIndex,
            level = "13+",
            levelValue = levelValue,
            noteDesigner = designer,
            notes = notes,
        )

    private fun scoreFor(chart: ChartRecord): ScoreRecord =
        ScoreRecord(
            id = "score-${chart.songId}",
            songId = chart.songId,
            title = chart.title,
            songType = chart.songType,
            difficulty = chart.difficulty,
            level = chart.level,
            levelIndex = chart.levelIndex,
            achievement = 100.5,
            dxScore = 2000,
            fc = "fc",
            fs = "fs",
            sourceBatchId = "batch",
            importedAt = 1L,
        )
}
