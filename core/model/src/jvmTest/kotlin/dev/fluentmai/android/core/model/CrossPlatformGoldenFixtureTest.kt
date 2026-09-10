package dev.fluentmai.android.core.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossPlatformGoldenFixtureTest {
    @Test
    fun `shared rating fixture is consumed by Kotlin`() {
        rows("rating/dx-rating.tsv").forEach { row ->
            assertEquals(
                row.getValue("expected_rating").toInt(),
                calculateSingleSongRating(
                    row.getValue("level_value").toDouble(),
                    row.getValue("achievement").toDouble(),
                ).rating,
                row.getValue("case"),
            )
        }
    }

    @Test
    fun `shared judgement loss fixture is consumed by Kotlin`() {
        rows("score-loss/judgement-loss.tsv").forEach { row ->
            val notes = MaimaiNoteCounts(
                tap = row.getValue("tap").toInt(),
                hold = row.getValue("hold").toInt(),
                slide = row.getValue("slide").toInt(),
                touch = row.getValue("touch").toInt(),
                breakCount = row.getValue("break").toInt(),
            )
            val result = calculateMaimaiAchievement(
                notes = notes,
                noteKind = MaimaiNoteKind.valueOf(row.getValue("note_kind")),
                judgement = MaimaiJudgement.valueOf(row.getValue("judgement")),
                occurrences = row.getValue("occurrences").toInt(),
                targetAchievement = row.getValue("target").toDouble(),
            )
            assertEquals(row.getValue("expected_loss").toDouble(), result.lossPerJudgement, 1e-12)
            assertEquals(row.getValue("expected_tolerated").toInt(), result.toleratedOccurrences)
            assertEquals(row.getValue("expected_result").toDouble(), result.resultingAchievement, 1e-12)
        }
    }

    @Test
    fun `shared finale plate fixture fixes Pandora membership`() {
        val fixture = rows("plate/finale-general.tsv")
        val charts = fixture.map { row ->
            val difficulty = Difficulty.fromLevelIndex(row.getValue("difficulty_index").toInt())!!
            ChartRecord(
                songId = row.getValue("song_id").toInt(),
                title = if (row.getValue("song_id") == "834") "PANDORA PARADOXXX" else "Other",
                artist = "Artist",
                genre = "maimai",
                bpm = 180,
                songVersion = row.getValue("chart_version").toInt(),
                songVersionName = "FiNALE",
                chartVersion = row.getValue("chart_version").toInt(),
                chartVersionName = "FiNALE",
                songType = if (row.getValue("chart_type") == "SD") SongType.STANDARD else SongType.DX,
                difficulty = difficulty,
                levelIndex = difficulty.levelIndex,
                level = "14+",
                levelValue = 14.9,
                noteDesigner = "Designer",
                notes = null,
            )
        }
        val scores = fixture.map { row ->
            val difficulty = Difficulty.fromLevelIndex(row.getValue("difficulty_index").toInt())!!
            ScoreRecord(
                id = "${row.getValue("song_id")}-${difficulty.name}",
                songId = row.getValue("song_id").toInt(),
                title = if (row.getValue("song_id") == "834") "PANDORA PARADOXXX" else "Other",
                songType = if (row.getValue("chart_type") == "SD") SongType.STANDARD else SongType.DX,
                difficulty = difficulty,
                level = "14+",
                levelIndex = difficulty.levelIndex,
                achievement = row.getValue("achievement").toDouble(),
                dxScore = null,
                fc = null,
                fs = null,
                sourceBatchId = "fixture",
                importedAt = 1L,
            )
        }
        val records = buildPlayerRecordCatalog(charts, scores).records
        val progress = calculatePlateProgress(records, PlateKind.GENERAL, 19_900, "FiNALE")

        assertEquals("輝将", progress.plateName)
        assertEquals(1, progress.requiredCount)
        assertEquals(834, progress.eligibleRecords.single().chart.songId)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `shared player stats fixture is consumed by Kotlin`() {
        val fixture = rows("player-records/stats.tsv")
        val charts = fixture.map { row -> chart(row.getValue("song_id").toInt(), 25_500, 13.5) }
        val scores = fixture.mapNotNull { row ->
            row.getValue("achievement").takeIf(String::isNotEmpty)?.toDouble()?.let { achievement ->
                score(
                    row.getValue("song_id").toInt(),
                    achievement,
                    row.getValue("full_combo").takeIf(String::isNotEmpty),
                    row.getValue("full_sync").takeIf(String::isNotEmpty),
                )
            }
        }
        val stats = buildPlayerRecordCatalog(charts, scores).stats()

        assertEquals(2, stats.playedCharts)
        assertEquals(1, stats.unplayedCharts)
        assertEquals(1, stats.rankCounts[AchievementRank.SSS_PLUS])
        assertEquals(1, stats.fullComboCounts[FullComboStatus.AP_PLUS])
        assertEquals(1, stats.fullSyncCounts[FullSyncStatus.FSD_PLUS])
    }

    @Test
    fun `shared B50 future batch fixture is consumed by Kotlin`() {
        var songId = 1
        val rated = rows("b50/future-version.tsv").flatMap { row ->
            val version = row.getValue("chart_version").toInt()
            val count = row.getValue("count").toInt()
            val constant = row.getValue("level_value").toDouble()
            (0 until count).map { index ->
                val chart = chart(songId, version, constant - index / 100.0)
                val score = score(songId, 100.5)
                songId += 1
                MaimaiRatedScore(score, chart, calculateDxRating(requireNotNull(chart.levelValue), score.achievement))
            }
        }
        val current = MaimaiCurrentVersion(
            MaimaiMajorVersion(25_500, "舞萌DX 2026"),
            MaimaiCurrentVersionSource.CATALOG_VERSION_TABLE,
        )
        val best = buildMaimaiBestSet(rated, current)

        assertEquals(35, best.oldBest.size)
        assertEquals(15, best.newBest.size)
        assertEquals(5, best.ineligible.size)
        assertTrue(best.newBest.all { it.chart?.chartVersion == 25_500 })
    }

    @Test
    fun `shared alias fixture is consumed by Kotlin`() {
        val aliases = rows("aliases/aliases.tsv")
            .map { row -> SongAliasEntry(row.getValue("song_id").toInt(), listOf(row.getValue("alias"))) }
        val catalog = SongAliasCatalog.from(aliases)

        assertEquals(listOf("PANDORA", "潘多拉"), catalog.aliasesFor(834))
    }

    private fun chart(songId: Int, version: Int, constant: Double) = ChartRecord(
        songId = songId,
        title = "Song $songId",
        artist = "Artist",
        genre = "maimai",
        bpm = 180,
        songVersion = version,
        songVersionName = "Version",
        chartVersion = version,
        chartVersionName = "Version",
        songType = SongType.DX,
        difficulty = Difficulty.MASTER,
        levelIndex = Difficulty.MASTER.levelIndex,
        level = "13+",
        levelValue = constant,
        noteDesigner = "Designer",
        notes = null,
    )

    private fun score(
        songId: Int,
        achievement: Double,
        fc: String? = null,
        fs: String? = null,
    ) = ScoreRecord(
        id = "score-$songId",
        songId = songId,
        title = "Song $songId",
        songType = SongType.DX,
        difficulty = Difficulty.MASTER,
        level = "13+",
        levelIndex = Difficulty.MASTER.levelIndex,
        achievement = achievement,
        dxScore = null,
        fc = fc,
        fs = fs,
        sourceBatchId = "fixture",
        importedAt = 1L,
    )

    private fun rows(relative: String): List<Map<String, String>> {
        val path = fixtureRoot().resolve(relative)
        val lines = Files.readAllLines(path, Charsets.UTF_8).filter(String::isNotBlank)
        val headers = lines.first().split('\t')
        return lines.drop(1).map { line ->
            val values = line.split('\t', ignoreCase = false, limit = headers.size)
            headers.mapIndexed { index, header -> header to values.getOrElse(index) { "" } }.toMap()
        }
    }

    private fun fixtureRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("test-fixtures")
            if (candidate.isDirectory()) return candidate
            current = current.parent
        }
        error("Unable to locate test-fixtures from ${Path.of("").toAbsolutePath()}")
    }
}
