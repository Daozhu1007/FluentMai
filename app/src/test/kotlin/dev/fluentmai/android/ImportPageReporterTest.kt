package dev.fluentmai.android

import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportPageReporterTest {
    @Test fun advancesOnlyAfterPageReturnsAndRetainsFailureCount() = runBlocking {
        val snapshots = mutableListOf<ImportProgress>()
        val reporter = ImportPageReporter(snapshots::add)
        assertEquals("html", reporter.page(ImportStage.Scores, 0, 5, "BASIC") {
            assertEquals(0, snapshots.last().processedPages)
            assertEquals(ImportPageState.Loading, snapshots.last().pageState)
            "html"
        })
        assertEquals(.2f, snapshots.last().fraction!!, .00001f)
        try { reporter.page(ImportStage.Scores, 1, 5, "ADVANCED") { throw java.io.IOException("token=secret") } }
        catch (_: java.io.IOException) { }
        assertEquals(ImportPageState.Failed, snapshots.last().pageState)
        assertEquals(1, snapshots.last().failedPages)
        reporter.page(ImportStage.Scores, 2, 5, "EXPERT") { "html" }
        assertEquals(3, snapshots.last().processedPages)
        assertEquals(1, snapshots.last().failedPages)
        assertTrue(snapshots.none { it.toString().contains("secret") })
    }

    @Test fun missingSupplementalPageDoesNotReportSuccess() = runBlocking {
        val snapshots = mutableListOf<ImportProgress>()
        ImportPageReporter(snapshots::add).page(ImportStage.Supplemental, 0, 1, "补充页", complete = { it.isNotEmpty() }) { emptyList<String>() }
        assertEquals(ImportPageState.Failed, snapshots.last().pageState)
        assertEquals(1, snapshots.last().failedPages)
    }

    @Test fun cancellationDoesNotAdvanceThePageCounter() = runBlocking {
        val snapshots = mutableListOf<ImportProgress>()
        try { ImportPageReporter(snapshots::add).page(ImportStage.Scores, 0, 5, "BASIC") { throw CancellationException() } }
        catch (_: CancellationException) { }
        assertEquals(1, snapshots.size)
        assertEquals(0, snapshots.last().processedPages)
    }

    @Test fun unknownTotalsDoNotInventPercentages() {
        assertNull(ImportProgress(ImportStage.Recent, "读取分页", processedPages = 2).fraction)
        assertNull(ImportProgress(ImportStage.PlayCounts, "没有页面", totalPages = 0).fraction)
        assertEquals(.3f, ImportProgress(ImportStage.PlayCounts, "停止", 3, 10, 3).fraction!!, .00001f)
        assertEquals("0.6%", ImportProgress(ImportStage.PlayCounts, "停止", 3, 500, 3).percentageLabel)
    }
}
