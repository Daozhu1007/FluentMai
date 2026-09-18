package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WahlapEfficientPcCaptureTest {
    private val missing = WahlapMusicDetailTarget("Outside", SongType.DX,
        "https://maimai.wahlap.com/maimai-mobile/record/musicDetail/?idx=synthetic", Difficulty.entries.toSet())

    @Test fun readsOnlyFiveRankingsAndUsesSeparateMinimumForEachDifficulty() = runBlocking {
        val requested = mutableListOf<String>()
        val saved = mutableListOf<ChartPlayCount>()
        val pages = mutableListOf<ImportProgress>()
        val result = captureEfficientPc(MaimaiSongCatalog.Empty, listOf(missing), fetch = { url ->
            requested += url
            val diff = url.substringAfter("diff=").toInt()
            card("Ranked", diff + 1) + card("Highest", 71)
        }, save = { saved += it }, onProgress = {}, onDiagnostic = {}, onPageProgress = pages::add)
        assertEquals(5, requested.size)
        assertTrue(requested.all { "musicMybest/search/" in it })
        assertTrue(result.warnings.isEmpty())
        assertEquals(10, result.chartCount)
        assertEquals(5, pages.last().processedPages)
        assertEquals(5, pages.last().totalPages)
        assertEquals(5, pages.count { it.pageState == ImportPageState.Complete })
        assertEquals(0, pages.last().failedPages)
        Difficulty.entries.forEach { diff ->
            val bound = saved.single { it.title == "Outside" && it.difficulty == diff }
            assertEquals("≤${diff.levelIndex + 1}", bound.displayText())
            assertTrue(bound.isUpperBound)
            assertFalse(saved.single { it.title == "Ranked" && it.difficulty == diff }.isUpperBound)
        }
    }

    @Test fun partialRankingCannotInventABoundAndKeepsPreviousData() = runBlocking {
        var writes = 0
        val result = captureEfficientPc(MaimaiSongCatalog.Empty, listOf(missing),
            fetch = { card("Ranked", 7) + "<div class='music_name_block'>Broken</div>" },
            save = { writes++ }, onProgress = {}, onDiagnostic = {})
        assertEquals(0, writes)
        assertEquals(5, result.warnings.size)
    }

    @Test fun standardAndDxAreSeparateAndUnsortedRankingStillUsesMinimum() {
        val ranked = listOf(ChartPlayCount("Same", SongType.STANDARD, Difficulty.MASTER, 3),
            ChartPlayCount("Top", SongType.DX, Difficulty.MASTER, 80))
        val snapshot = pcRankingSnapshot(Difficulty.MASTER, ranked, emptyList(), listOf(missing.copy(title = "Same")))
        assertEquals("3", snapshot.single { it.title == "Same" && it.songType == SongType.STANDARD }.displayText())
        assertEquals("≤3", snapshot.single { it.title == "Same" && it.songType == SongType.DX }.displayText())
    }

    @Test fun catalogChartsWithoutAnyPlayerScoreAlsoReceiveTheBound() {
        val chart = ChartRecord(1, "Unplayed", "Artist", "maimai", null, 1, null, 1, null,
            SongType.DX, Difficulty.MASTER, 3, "14", 14.0, "", null)
        val snapshot = pcRankingSnapshot(Difficulty.MASTER,
            listOf(ChartPlayCount("Ranked", SongType.DX, Difficulty.MASTER, 6)), listOf(chart), emptyList())
        assertEquals("≤6", snapshot.single { it.title == "Unplayed" }.displayText())
    }

    @Test fun emptyRankingsDoNotInventZeroBounds() = runBlocking {
        var writes = 0
        val result = captureEfficientPc(MaimaiSongCatalog.Empty, listOf(missing),
            fetch = { "<html><body><a href='/record/musicMybest/'>我的最好成绩</a></body></html>" },
            save = { writes++ }, onProgress = {}, onDiagnostic = {})
        assertEquals(0, writes)
        assertEquals(5, result.warnings.size)
    }

    private fun card(title: String, count: Int) = "<div class='w_450'><div class='music_name_block'>$title</div><div>游戏次数：$count</div><img src='music_dx.png'></div>"
}
