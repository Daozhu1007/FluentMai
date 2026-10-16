package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportDownloadDiagnosticsTest {
    private fun report(logs: List<WahlapAttemptLog>): ImportDiagnosticReport {
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, ImportMonotonicClock { 0 }) { 0 }
        collector.beginImport()
        collector.started(DiagnosticStage.EXPERT)
        logs.forEach { collector.attempt(WahlapRequestCategory.SCORE_PAGE, DiagnosticRequestLabel.EXPERT, it) }
        collector.finished(DiagnosticStage.EXPERT, failed = true, interrupted = false)
        return collector.finish(ImportTaskState(phase = ImportTaskPhase.Finished))
    }
    private fun log(reason: DiagnosticDownloadFailure? = DiagnosticDownloadFailure.READ_IDLE_TIMEOUT) = WahlapAttemptLog(
        "score-page", 1, 3, 100, WahlapAttemptOutcome.RETRYABLE_FAILURE, true, 200,
        responseBytes = 1234, progressChunks = 4, downloadFailure = reason)

    @Test fun typedReasonsAndByteCountsRoundTripWithoutExceptionTextOrBody() {
        for (reason in DiagnosticDownloadFailure.entries) {
            val report = report(listOf(log(reason).copy(errorType = "https://synthetic.invalid/?token=private-marker")))
            val json = ImportDiagnosticJson.encode(report)
            assertTrue(json.contains(reason.name.lowercase()))
            assertFalse(json.contains("private-marker"))
            assertFalse(json.contains("https://"))
            assertEquals(report.requests, ImportDiagnosticJson.decode(json).requests)
            assertEquals(1234L, report.requests.single().responseBytes)
            assertEquals(4L, report.requests.single().progressChunks)
        }
    }

    @Test fun oldSchemaOneReportsWithoutNewFieldsStillDecode() {
        val original = report(listOf(log()))
        val json = JSONObject(ImportDiagnosticJson.encode(original))
        val request = json.getJSONArray("requests").getJSONObject(0)
        listOf("response_bytes", "progress_chunks", "last_download_failure").forEach(request::remove)
        val decoded = ImportDiagnosticJson.decode(json.toString())
        assertEquals(1, decoded.schemaVersion)
        assertEquals(original.requests.single().copy(responseBytes = null, progressChunks = null, lastDownloadFailure = null),
            decoded.requests.single())
    }

    @Test fun aggregateBytesIncludeFailedAttemptsAndRetainObservedLastFailure() {
        val report = report(listOf(log(), log(null).copy(attempt = 2, outcome = WahlapAttemptOutcome.SUCCESS, willRetry = false)))
        val stats = report.requests.single()
        assertEquals(2468L, stats.responseBytes)
        assertEquals(8L, stats.progressChunks)
        assertEquals(DiagnosticDownloadFailure.READ_IDLE_TIMEOUT, stats.lastDownloadFailure)
        assertEquals(1L, stats.successfulRequests)
        assertEquals(1L, stats.retries)
    }

    @Test fun largeInjectedProgressCountersAreBounded() {
        val report = report(listOf(log()))
        val injected = report.copy(requests = listOf(report.requests.single().copy(responseBytes = Long.MAX_VALUE, progressChunks = Long.MAX_VALUE)))
        val decoded = ImportDiagnosticJson.decode(ImportDiagnosticJson.encode(injected))
        assertTrue(decoded.metricsBounded)
        assertEquals(MAX_DIAGNOSTIC_NUMBER, decoded.requests.single().responseBytes)
        assertEquals(MAX_DIAGNOSTIC_NUMBER, decoded.requests.single().progressChunks)
    }
}
