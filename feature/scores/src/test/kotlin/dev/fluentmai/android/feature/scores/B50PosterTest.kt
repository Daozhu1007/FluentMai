package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.*
import org.junit.Assert.*
import org.junit.Test

class B50PosterTest {
    @Test fun starsRespectThresholdsAndUnknownScores() {
        assertNull(posterDxStars(null, 100))
        assertNull(posterDxStars(300, null))
        assertNull(posterDxStars(301, 100))
        assertEquals(0, posterDxStars(254, 100))
        assertEquals(1, posterDxStars(255, 100))
        assertEquals(2, posterDxStars(270, 100))
        assertEquals(3, posterDxStars(279, 100))
        assertEquals(4, posterDxStars(285, 100))
        assertEquals(5, posterDxStars(291, 100))
    }

    @Test fun matchesHomeBestSetAndDoesNotFabricateMissingSlots() {
        val charts = (1..60).map { chart(it) }
        val scores = charts.map { score(it) }
        val best = posterBestSet(scores, charts, listOf(MaimaiMajorVersion(25500, "舞萌DX 2026")))
        assertEquals(35, best.oldBest.size)
        assertEquals(15, best.newBest.size)
        assertEquals(best.all.sumOf { it.rating!! }, best.rating)
        assertTrue(best.newBest.all { it.chart!!.chartVersion == 25500 })
        val few = posterBestSet(scores.take(2), charts, listOf(MaimaiMajorVersion(25500, "舞萌DX 2026")))
        assertEquals(2, few.all.size)
    }

    @Test fun ratingFrameChangesAtGameThresholds() {
        assertEquals("gold", posterRatingColor(14499))
        assertEquals("platinum", posterRatingColor(14500))
        assertEquals("rainbow", posterRatingColor(15000))
        assertEquals("normal", posterRatingColor(0))
        assertEquals("orange", posterRatingColor(4000))
        assertTrue(B50Assets.rating(6999).endsWith("rating_base_orange.png"))
    }

    companion object {
        fun chart(id: Int): ChartRecord = ChartRecord(
            songId = id, title = if (id % 3 == 0) "测试长曲名 · 星のメロディ / World's End" else "Rhythm of the Stars $id",
            artist = "Artist", genre = "Original", bpm = 180, songVersion = 25000, songVersionName = "2025",
            chartVersion = if (id <= 40) 25000 else 25500, chartVersionName = "2026",
            songType = if (id % 2 == 0) SongType.DX else SongType.STANDARD,
            difficulty = Difficulty.entries[id % 5], levelIndex = id % 5, level = "14+", levelValue = 14.9,
            noteDesigner = "Designer", notes = ChartNotes(1000, 700, 50, 150, 50, 50),
        )
        fun score(chart: ChartRecord): ScoreRecord = ScoreRecord(
            id = chart.songId.toString(), songId = chart.songId, title = chart.title,
            songType = chart.songType, difficulty = chart.difficulty, level = chart.level, levelIndex = chart.levelIndex,
            achievement = 100.5, dxScore = 2960, fc = "app", fs = "fsdp", sourceBatchId = "test", importedAt = 0,
        )
    }
}
