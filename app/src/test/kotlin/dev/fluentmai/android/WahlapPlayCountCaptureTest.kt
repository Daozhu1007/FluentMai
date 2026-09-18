package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapMusicDetailTarget
import dev.fluentmai.android.core.importer.WahlapPlayCountParser
import dev.fluentmai.android.core.model.ChartPlayCount
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WahlapPlayCountCaptureTest {
    @Test fun fetchesEverySongIncludingOnePlayAndRefreshesCountsWhenBestScoreIsUnchanged() = runBlocking {
        val targets = (1..180).map { target("Song $it") }
        val counts = mutableMapOf<String, Int>()
        val progress = mutableListOf<String>()
        var requests = 0
        var pc = 1
        suspend fun capture() = captureWahlapPlayCounts(targets,
            fetch = { target -> requests++; html(target.title, pc) },
            save = { rows -> rows.forEach { counts[it.title] = it.count } },
            onProgress = { progress += it }, pause = {},
        )
        assertEquals(180, capture().chartCount)
        assertEquals(180, requests)
        assertEquals(1, counts["Song 180"])
        pc = 2
        assertTrue(capture().warnings.isEmpty())
        assertEquals(2, counts["Song 180"])
        assertTrue(progress.last().contains("180/180"))
    }

    @Test fun failedPagePreservesPreviousPcAndDoesNotPreventNextSong() = runBlocking {
        val targets = listOf(target("First"), target("Failure"), target("Last"))
        val counts = mutableMapOf("Failure" to 7)
        val result = captureWahlapPlayCounts(targets,
            fetch = { target -> if (target == targets[1]) "<html>登录失败</html>" else html(target.title, 1) },
            save = { rows -> rows.forEach { counts[it.title] = it.count } }, pause = {},
        )
        assertEquals(7, counts["Failure"])
        assertEquals(1, counts["Last"])
        assertEquals(2, result.chartCount)
        assertTrue(result.warnings.single().contains("1 首未完整取得"))
    }

    @Test fun partialDifficultyDataIsSavedButNotReportedAsComplete() = runBlocking {
        val target = target("Test").copy(difficulties = setOf(Difficulty.EXPERT, Difficulty.MASTER))
        val saved = mutableListOf<ChartPlayCount>()
        val result = captureWahlapPlayCounts(listOf(target), { html("Test", 1) }, { saved.addAll(it) }, pause = {})
        assertEquals(1, saved.size)
        assertTrue(result.warnings.single().contains("部分已游玩难度未返回 PC"))
    }

    @Test fun stopsAfterRepeatedFailureAndReportsUnvisitedSongs() = runBlocking {
        var requests = 0
        val pages = mutableListOf<dev.fluentmai.android.core.model.ImportProgress>()
        val result = captureWahlapPlayCounts((1..10).map { target("Song $it") },
            fetch = { requests++; "<html>错误页面</html>" }, save = { fail("must not overwrite PC") }, pause = {},
            onPageProgress = pages::add,
        )
        assertEquals(3, requests)
        assertEquals(0, result.chartCount)
        assertTrue(result.warnings.single().contains("7 首尚未读取"))
        assertEquals(3, pages.last().processedPages)
        assertEquals(10, pages.last().totalPages)
        assertEquals(3, pages.last().failedPages)
        assertEquals(.3f, pages.last().fraction!!, .00001f)
    }

    @Test fun temporaryNetworkFailureRetriesWithoutDuplicateSave() = runBlocking {
        var requests = 0
        var saves = 0
        val pages = mutableListOf<dev.fluentmai.android.core.model.ImportProgress>()
        val result = captureWahlapPlayCounts(listOf(target("Test")),
            fetch = { if (++requests == 1) throw java.net.SocketTimeoutException("sensitive query") else html("Test", 3) },
            save = { saves++ }, pause = {},
            onPageProgress = pages::add,
        )
        assertEquals(2, requests)
        assertEquals(1, saves)
        assertTrue(result.warnings.isEmpty())
        assertEquals(1, pages.last().processedPages)
        assertEquals(0, pages.last().failedPages)
        assertEquals(1, pages.count { it.pageState == dev.fluentmai.android.core.model.ImportPageState.Complete })
    }

    @Test fun cancellationIsNotSwallowedOrRetried() = runBlocking {
        var requests = 0
        try {
            captureWahlapPlayCounts(listOf(target("Test")),
                fetch = { requests++; throw CancellationException() }, save = { fail("cancelled") }, pause = {},
            )
            fail("expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, requests) }
    }

    @Test fun rejectedDetailRefreshesListAndUsesNewIdxInsteadOfReplayingRejectedRequest() = runBlocking {
        val old = target("Test")
        val fresh = old.copy(url = "${WahlapPlayCountParser.DETAIL_URL}?idx=fresh")
        val events = mutableListOf<String>()
        val result = captureWahlapPlayCounts(listOf(old), fetch = {
            if (it.url == old.url) {
                events += "old"
                throw WahlapActivityFetchException("官方返回错误页面（HTTP 200）", canRefreshDetail = true)
            }
            events += "fresh"
            html("Test", 1)
        }, save = { events += "save" }, pause = {}, refreshTarget = {
            assertEquals(old, it)
            events += "list"
            fresh
        })
        assertEquals(listOf("old", "list", "fresh", "save"), events)
        assertEquals(1, result.chartCount)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun failedRecoveryDoesNotLoopOrEraseOldCounts() = runBlocking {
        var fetches = 0
        var refreshes = 0
        val result = captureWahlapPlayCounts(listOf(target("Test")), fetch = {
            fetches++
            throw WahlapActivityFetchException("官方错误码 1234", canRefreshDetail = true)
        }, save = { fail("keep original PC") }, pause = {}, refreshTarget = { refreshes++; it })
        assertEquals(2, fetches)
        assertEquals(1, refreshes)
        assertTrue(result.warnings.single().contains("1234"))
    }

    @Test fun refreshListFailurePreservesBothErrorsAndFullStackInDiagnostics() = runBlocking {
        val diagnostics = mutableListOf<String>()
        var refreshes = 0
        var pauses = 0
        val result = captureWahlapPlayCounts(listOf(target("Test")), fetch = {
            throw WahlapActivityFetchException("官方错误码 4321", canRefreshDetail = true)
        }, save = { fail("must preserve previous PC") }, pause = {}, refreshTarget = {
            refreshes++
            throw java.io.IOException("score MASTER unexpected page")
        }, onDiagnostic = { diagnostics += it }, recoveryPause = { pauses++ })
        assertEquals(2, refreshes)
        assertEquals(1, pauses)
        assertTrue(result.warnings.single().contains("4321"))
        assertTrue(result.warnings.single().contains("重新获取成绩列表"))
        val report = diagnostics.joinToString("\n")
        assertTrue(report.contains("首次详情失败"))
        assertTrue(report.contains("score MASTER unexpected page"))
        assertTrue(report.contains("WahlapPlayCountCaptureTest"))
    }

    private fun target(title: String) = WahlapMusicDetailTarget(title, SongType.DX,
        "${WahlapPlayCountParser.DETAIL_URL}?idx=${title.replace(" ", "")}", setOf(Difficulty.MASTER))
    private fun html(title: String, count: Int) = """
        <div class="m_5 f_15 break">$title</div><table class="music_detail_table"></table>
        <div id="master" class="music_master_score_back"><img src="music_dx.png"><div>游戏次数：$count</div></div>
    """
}
