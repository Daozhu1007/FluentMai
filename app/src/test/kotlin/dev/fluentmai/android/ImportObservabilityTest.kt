package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportObservabilityTest {
    private class Clock : ImportMonotonicClock {
        var ms = 0L
        override fun nowNanos() = ms * 1_000_000
        fun advance(value: Long) { ms += value }
    }
    private fun completed(result: RealWahlapImportResult = successful(), pageFailed: Boolean = false) =
        ImportTaskState(phase = ImportTaskPhase.Finished, result = result, pageFailed = pageFailed)
    private fun successful() = RealWahlapImportResult(ImportResult("private-batch", 3, 0, 0, 0, 0), 3, 5, 0, emptyList())
    private fun report(outcome: DiagnosticOutcome = DiagnosticOutcome.COMPLETE) = ImportDiagnosticReport(
        appVersion = APP_VERSION, createdAtEpochMs = 1_700_000_000_000, mode = DiagnosticImportMode.MANUAL_COOKIE,
        outcome = outcome, termination = DiagnosticTermination.FINISHED, totalDurationMs = 100,
        executionDurationMs = 100, authorizationWaitDurationMs = null, unattributedDurationMs = 10,
        stages = DiagnosticStage.entries.map { DiagnosticStageTiming(it) },
        requests = listOf(DiagnosticRequestStats(DiagnosticStage.MASTER, DiagnosticRequestCategory.SCORE_PAGE, DiagnosticRequestLabel.MASTER, 1)),
        failures = setOf(DiagnosticFailure.REQUEST), authMilestones = listOf(DiagnosticAuthMilestone(DiagnosticAuthEvent.REQUESTED, 0)))

    @Test fun injectedClockMeasuresNestedStagesAndUnattributedOverhead() {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock) { 1000 }
        collector.beginImport()
        clock.advance(10)
        collector.measure(DiagnosticStage.RECENT_RECORDS) {
            clock.advance(20)
            collector.measure(DiagnosticStage.DATABASE_PERSISTENCE) { clock.advance(30) }
            clock.advance(40)
        }
        clock.advance(50)
        val report = collector.finish(completed())
        assertEquals(150L, report.totalDurationMs)
        val recent = report.stages.first { it.stage == DiagnosticStage.RECENT_RECORDS }
        assertEquals(90L, recent.durationMs); assertEquals(60L, recent.exclusiveDurationMs)
        assertEquals(30L, report.stages.first { it.stage == DiagnosticStage.DATABASE_PERSISTENCE }.durationMs)
        assertEquals(60L, report.unattributedDurationMs)
        assertNull(report.stages.first { it.stage == DiagnosticStage.PC_CAPTURE }.durationMs)
    }
    @Test fun oauthWaitingAndImportTotalsAreSeparate() {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.WECHAT_OAUTH, clock)
        clock.advance(500)
        collector.beginImport()
        collector.measure(DiagnosticStage.LOGIN_HOME) { clock.advance(80) }
        val report = collector.finish(completed())
        assertEquals(500L, report.authorizationWaitDurationMs)
        assertEquals(80L, report.totalDurationMs)
        assertEquals(580L, report.executionDurationMs)
    }
    private fun requestFixture(failures: Int, maxAttempts: Int = 3): ImportDiagnosticReport = runBlocking {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock)
        collector.beginImport()
        val fetcher = WahlapResilientFetcher(nowMs = { clock.ms }, delayFor = { clock.advance(it) })
        var attempt = 0
        try {
            collector.measure(DiagnosticStage.MASTER) {
                fetcher.fetch(WahlapRequestCategory.SCORE_PAGE,
                    retryPolicy = WahlapRetryPolicy(maxAttempts, 100, 100, 1.0, 0.0),
                    onAttempt = { collector.attempt(WahlapRequestCategory.SCORE_PAGE, DiagnosticRequestLabel.MASTER, it) }) { _, meta ->
                    attempt++
                    clock.advance(20)
                    meta.httpStatus = if (attempt <= failures) 503 else 200
                    meta.responseChars = 512
                    if (attempt <= failures) throw WahlapHttpStatusException(503)
                    "synthetic response"
                }
            }
        } catch (_: IOException) { }
        collector.finish(completed())
    }
    @Test fun httpRetryUsesExecutedAttemptsAndIncludesBackoffOnlyInStage() {
        val report = requestFixture(1)
        val request = report.requests.single()
        assertEquals(2L, request.attempts); assertEquals(1L, request.retries)
        assertEquals(1L, request.successfulRequests); assertEquals(0L, request.failedRequests)
        assertEquals(40L, request.attemptDurationMs)
        assertEquals(140L, report.stages.first { it.stage == DiagnosticStage.MASTER }.durationMs)
        assertEquals(200, request.lastHttpStatus); assertEquals(1024L, request.responseChars)
        assertEquals(mapOf(503 to 1L, 200 to 1L), request.httpStatusCounts.associate { it.status to it.attempts })
    }
    @Test fun firstAttemptSuccessHasZeroMeasuredRetries() {
        val request = requestFixture(0).requests.single()
        assertEquals(1L, request.attempts); assertEquals(0L, request.retries)
        assertEquals(20L, request.attemptDurationMs)
    }
    @Test fun exhaustedRetriesCountOneFailedRequest() {
        val request = requestFixture(3).requests.single()
        assertEquals(3L, request.attempts); assertEquals(2L, request.retries)
        assertEquals(1L, request.failedRequests); assertEquals(0L, request.successfulRequests)
        assertEquals(503, request.lastHttpStatus)
    }
    @Test fun manyPcPagesRemainOneBoundedAggregate() {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock)
        collector.beginImport()
        collector.measure(DiagnosticStage.PC_CAPTURE) {
            repeat(10_000) {
                clock.advance(2)
                collector.attempt(WahlapRequestCategory.SUPPLEMENTAL_PAGE, DiagnosticRequestLabel.MUSIC_DETAIL,
                    WahlapAttemptLog("injected-ignored", 1, 2, 2, WahlapAttemptOutcome.SUCCESS, false, 200, 20))
            }
        }
        val report = collector.finish(completed())
        assertEquals(1, report.requests.size)
        assertEquals(10_000L, report.requests.single().attempts)
        assertEquals(20_000L, report.stages.first { it.stage == DiagnosticStage.PC_CAPTURE }.durationMs)
        assertTrue(ImportDiagnosticJson.encode(report).length < 12_000)
    }
    private class MemoryPersistence(private val tick: () -> Unit = {}) : ImportPersistence {
        val scores = mutableMapOf<String, ScoreRecord>()
        var batches = 0
        override suspend fun findExistingScoreIds(scoreIds: Set<String>): Set<String> { tick(); return scoreIds.intersect(scores.keys) }
        override suspend fun insertScoreRecords(records: List<ScoreRecord>) { tick(); records.forEach { scores[it.id] = it } }
        override suspend fun insertQuarantineRecords(records: List<QuarantineRecord>) { tick() }
        override suspend fun insertImportBatch(batch: ImportBatch) { tick(); batches++ }
    }
    private suspend fun imported(collector: ImportDiagnosticCollector, persistence: ImportPersistence,
        failed: Difficulty? = null, supplementalFailure: Boolean = false, observer: ImportTimingObserver = collector): RealWahlapImportResult {
        val html = File("../fixtures/wahlap_valid_fixture.html").readText()
        return RealWahlapImportAdapter(observer = observer).importFetchedPages("synthetic",
            WahlapScorePageProvider { difficulty -> collector.measure(DiagnosticStage.valueOf(difficulty.name)) {
                if (failed == difficulty) throw IOException("Cookie: must-never-export")
                html
            } }, DiagnosticImportPersistence(persistence, observer),
            WahlapSupplementalPageProvider { collector.measure(DiagnosticStage.SUPPLEMENTAL) {
                if (supplementalFailure) WahlapSupplementalFetchResult(failures = listOf(WahlapSupplementalFailure("untrusted-label", "untrusted-secret")))
                else WahlapSupplementalFetchResult()
            } })
    }
    @Test fun multipleDifficultyParserAndPersistenceHooksMeasureRealSyntheticPipeline() = runBlocking {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock)
        collector.beginImport()
        val observer = object : ImportTimingObserver {
            override fun started(stage: DiagnosticStage) = collector.started(stage)
            override fun finished(stage: DiagnosticStage, failed: Boolean, interrupted: Boolean) {
                if (stage == DiagnosticStage.PARSING) clock.advance(3)
                collector.finished(stage, failed, interrupted)
            }
        }
        val persistence = MemoryPersistence { clock.advance(7) }
        val result = imported(collector, persistence, observer = observer)
        collector.result(result)
        val report = collector.finish(completed(result))
        assertEquals(DiagnosticOutcome.COMPLETE, report.outcome)
        Difficulty.entries.forEach { difficulty -> assertEquals(DiagnosticObservation.COMPLETE, report.stages.first { it.stage.name == difficulty.name }.observation) }
        assertEquals(15L, report.stages.first { it.stage == DiagnosticStage.PARSING }.durationMs)
        assertEquals(28L, report.stages.first { it.stage == DiagnosticStage.DATABASE_PERSISTENCE }.durationMs)
        assertEquals(15L, report.counters.parsed)
        assertNull(report.counters.playCountCharts)
        assertEquals(1, persistence.batches)
    }
    @Test fun partialReportRetainsPreviouslyPersistedFailedDifficultyWithoutExtraBatch() = runBlocking {
        val persistence = MemoryPersistence()
        val first = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        first.beginImport(); imported(first, persistence)
        val previousMaster = persistence.scores.filterValues { it.difficulty == Difficulty.MASTER }
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        val result = imported(collector, persistence, failed = Difficulty.MASTER)
        collector.result(result)
        val report = collector.finish(completed(result))
        assertEquals(DiagnosticOutcome.PARTIAL, report.outcome)
        assertEquals(previousMaster, persistence.scores.filterValues { it.difficulty == Difficulty.MASTER })
        assertEquals(2, persistence.batches)
        assertFalse(ImportDiagnosticJson.encode(report).contains("must-never-export"))
    }
    @Test fun supplementalFailureProducesPartialAndFixedFailureCategory() = runBlocking {
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        val result = imported(collector, MemoryPersistence(), supplementalFailure = true)
        collector.result(result)
        val report = collector.finish(completed(result))
        assertEquals(DiagnosticOutcome.PARTIAL, report.outcome)
        assertTrue(DiagnosticFailure.SUPPLEMENTAL in report.failures)
        assertEquals(DiagnosticObservation.FAILED, report.stages.first { it.stage == DiagnosticStage.SUPPLEMENTAL }.observation)
        assertFalse(ImportDiagnosticJson.encode(report).contains("untrusted"))
    }
    @Test fun failedReportUsesCurrentResultEvenIfUnrelatedDataAlreadyExists() = runBlocking {
        val persistence = MemoryPersistence()
        val previous = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        previous.beginImport(); imported(previous, persistence)
        val existing = persistence.scores.toMap()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        val result = RealWahlapImportAdapter(observer = collector).importFetchedPages("synthetic-failed",
            WahlapScorePageProvider { throw IOException("synthetic unavailable") }, DiagnosticImportPersistence(persistence, collector))
        collector.result(result)
        assertEquals(DiagnosticOutcome.FAILED, collector.finish(completed(result)).outcome)
        assertEquals(existing, persistence.scores)
        assertEquals(1, persistence.batches)
    }
    @Test fun existingCompletionCriteriaDowngradeWarningsAndPageFailures() {
        listOf(completed(successful().copy(activityWarnings = listOf("secret"))), completed(pageFailed = true)).forEach { state ->
            assertEquals(DiagnosticOutcome.PARTIAL, ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock()).finish(state).outcome)
        }
    }
    @Test fun oauthRejectionIsSeparateFromImportOutcomeAndRetryAvailability() {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.WECHAT_OAUTH, clock)
        clock.advance(100); collector.beginImport()
        collector.measure(DiagnosticStage.LOGIN_HOME) { clock.advance(20) }
        val report = collector.finish(ImportTaskState(phase = ImportTaskPhase.AuthRetryAvailable,
            failureCategory = ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED))
        assertNull(report.outcome); assertEquals(DiagnosticTermination.AUTH_REJECTED, report.termination)
        assertTrue(DiagnosticFailure.AUTHORIZATION_REJECTED in report.failures)
        assertNull(report.counters.parsed)
        assertEquals(DiagnosticObservation.UNOBSERVED, report.stages.first { it.stage == DiagnosticStage.MASTER }.observation)
    }
    @Test fun cancellationKeepsIncompleteScopeAndNeverBecomesSuccess() {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock)
        collector.beginImport()
        try { collector.measure(DiagnosticStage.MASTER) { clock.advance(8); throw CancellationException("private-token") } }
        catch (_: CancellationException) { }
        val report = collector.finish(completed().copy(failureCategory = ImportFailureCategory.CANCELLED))
        assertNull(report.outcome); assertEquals(DiagnosticTermination.CANCELLED, report.termination)
        assertEquals(DiagnosticObservation.INCOMPLETE, report.stages.first { it.stage == DiagnosticStage.MASTER }.observation)
        assertFalse(ImportDiagnosticJson.encode(report).contains("private-token"))
    }
    @Test fun sealedCollectorIgnoresLateWorkerEvents() {
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        val report = collector.finish(completed())
        collector.started(DiagnosticStage.MASTER)
        collector.failure(DiagnosticFailure.PERSISTENCE)
        assertEquals(report, collector.finish(completed()))
    }
    @Test fun schemaIsStableParseableAndRoundTripsAllOutcomes() {
        DiagnosticOutcome.entries.forEach { outcome ->
            val json = ImportDiagnosticJson.encode(report(outcome))
            val parsed = JSONObject(json)
            assertEquals(1, parsed.getInt("schema_version"))
            assertEquals(outcome.name, parsed.getString("outcome"))
            assertEquals(json, ImportDiagnosticJson.encode(ImportDiagnosticJson.decode(json)))
            assertEquals(setOf("schema_version", "app_version", "created_at_epoch_ms", "import_mode", "outcome", "termination",
                "total_duration_ms", "execution_duration_ms", "authorization_wait_duration_ms", "unattributed_duration_ms",
                "stages", "requests", "request_observation", "counters", "failure_categories", "auth_milestones", "metrics_bounded"), parsed.keySet())
        }
    }
    @Test fun oversizedListsAndNumbersRemainBounded() {
        val huge = report().copy(appVersion = "private".repeat(100_000), requests = List(100_000) { report().requests.single().copy(attempts = Long.MAX_VALUE) },
            stages = List(100_000) { DiagnosticStageTiming(DiagnosticStage.MASTER, DiagnosticObservation.COMPLETE, Long.MAX_VALUE) })
        val json = ImportDiagnosticJson.encode(huge)
        assertTrue(json.toByteArray().size <= ImportDiagnosticJson.MAX_BYTES)
        assertTrue(JSONObject(json).getBoolean("metrics_bounded"))
        assertFalse(json.contains("private"))
        assertEquals(MAX_DIAGNOSTIC_NUMBER, JSONObject(json).getJSONArray("requests").getJSONObject(0).getLong("attempts"))
    }
    @Test fun arbitraryLegacyAttemptFieldsAndDiagnosticTextNeverReachExport() {
        val attack = "https://example.invalid/?code=SENSITIVE_SENTINEL&state=SENSITIVE_SENTINEL Cookie: SENSITIVE_SENTINEL <html>SENSITIVE_SENTINEL</html> C:\\private\\SENSITIVE_SENTINEL"
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        collector.attempt(WahlapRequestCategory.SCORE_PAGE, diagnosticRequestLabel(attack),
            WahlapAttemptLog(attack, 1, 3, 20, WahlapAttemptOutcome.NON_RETRYABLE_FAILURE, false, errorType = attack))
        val result = successful().copy(diagnosticDetails = attack, failures = listOf(WahlapDifficultyFailure(Difficulty.MASTER, attack)),
            supplementalFailures = listOf(WahlapSupplementalFailure(attack, attack)), activityWarnings = listOf(attack))
        collector.result(result)
        val json = ImportDiagnosticJson.encode(collector.finish(completed(result)))
        assertFalse(json.contains("SENSITIVE_SENTINEL")); assertFalse(json.contains("https://")); assertFalse(json.contains("<html>"))
        assertEquals(DiagnosticRequestLabel.OTHER.name, JSONObject(json).getJSONArray("requests").getJSONObject(0).getString("label"))
    }
    @Test fun everyExportedStringFieldRejectsOrDropsSensitiveInjection() {
        val clean = JSONObject(ImportDiagnosticJson.encode(report()))
        val paths = mutableListOf<List<Any>>()
        fun visit(value: Any, path: List<Any>) {
            when (value) {
                is JSONObject -> value.keySet().forEach { visit(value.get(it), path + it) }
                is org.json.JSONArray -> (0 until value.length()).forEach { visit(value.get(it), path + it) }
                is String -> paths += path
            }
        }
        visit(clean, emptyList())
        assertTrue(paths.size > 40)
        val attacks = listOf("https://example.invalid/SENSITIVE_SENTINEL", "Cookie: SENSITIVE_SENTINEL", "Set-Cookie: SENSITIVE_SENTINEL",
            "Authorization: Bearer SENSITIVE_SENTINEL", "<html>SENSITIVE_SENTINEL</html>", "code=SENSITIVE_SENTINEL&state=SENSITIVE_SENTINEL&r=SENSITIVE_SENTINEL&t=SENSITIVE_SENTINEL",
            "C:\\private\\SENSITIVE_SENTINEL", "SENSITIVE_SENTINEL")
        paths.forEach { path -> attacks.forEach { attack ->
            val root = JSONObject(clean.toString())
            var parent: Any = root
            path.dropLast(1).forEach { parent = if (it is String) (parent as JSONObject).get(it) else (parent as org.json.JSONArray).get(it as Int) }
            val last = path.last()
            if (last is String) (parent as JSONObject).put(last, attack) else (parent as org.json.JSONArray).put(last as Int, attack)
            try {
                val exported = ImportDiagnosticJson.encode(ImportDiagnosticJson.decode(root.toString()))
                assertFalse("Injection crossed $path", exported.contains("SENSITIVE_SENTINEL"))
            } catch (_: IllegalArgumentException) { }
        } }
    }
    @Test fun quickAuthTimingSnapshotUsesExistingOneShotMilestones() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timing = QuickAuthTiming({ now }, logs::add)
        timing.mark(QuickAuthEvent.Requested)
        now = 10; timing.mark(QuickAuthEvent.CaptureReady)
        now = 20; timing.mark(QuickAuthEvent.AuthorizeGenerated)
        now = 21; timing.mark(QuickAuthEvent.ClipboardReady)
        now = 22; timing.mark(QuickAuthEvent.LaunchDispatched)
        now = 200; timing.mark(QuickAuthEvent.CallbackCaptured)
        timing.mark(QuickAuthEvent.CallbackCaptured)
        assertEquals(6, logs.size)
        assertEquals(200L, timing.snapshot().last().elapsedMs)
        assertEquals(DiagnosticAuthEvent.CALLBACK_CAPTURED, timing.snapshot().last().event)
    }
    @Test fun existingOuterRecoveryRetriesRemainSeparateFromHttpAttempts() = runBlocking {
        val clock = Clock()
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, clock)
        collector.beginImport()
        var calls = 0
        collector.measure(DiagnosticStage.PC_CAPTURE) {
            retryActivityFetch(onRetryFailure = { collector.recoveryRetry(DiagnosticStage.PC_CAPTURE) }) {
                calls++
                if (calls == 1) throw IOException("synthetic")
                "recovered"
            }
        }
        val report = collector.finish(completed())
        assertEquals(1L, report.stages.first { it.stage == DiagnosticStage.PC_CAPTURE }.recoveryRetries)
        assertTrue(report.requests.isEmpty())
    }
    @Test fun documentationSyntheticSampleUsesProductionSchemaAndCorrectExclusiveAccounting() {
        val report = ImportDiagnosticJson.decode(File("../docs/research/auth-net/device-history/IMPORT-OBS-1.synthetic.json").readText())
        assertEquals(DiagnosticOutcome.PARTIAL, report.outcome)
        assertEquals(report.totalDurationMs, report.stages.sumOf { it.exclusiveDurationMs ?: 0 } + report.unattributedDurationMs!!)
        assertEquals(1, JSONObject(ImportDiagnosticJson.encode(report)).getInt("schema_version"))
    }
    @Test fun manualCookieAuthorizationRejectionHasNoScoreOutcomeOrFabricatedCounters() {
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        collector.failure(DiagnosticFailure.AUTHORIZATION_REJECTED)
        val report = collector.finish(ImportTaskState(phase = ImportTaskPhase.Finished, failureCategory = ImportFailureCategory.NETWORK_TRANSPORT))
        assertEquals(DiagnosticTermination.AUTH_REJECTED, report.termination)
        assertNull(report.outcome); assertNull(report.counters.parsed)
    }
    @Test fun maximumAggregateDetailFitsByteLimitByExplicitlyTruncatingDetail() {
        val detail = DiagnosticStage.entries.flatMap { stage -> DiagnosticRequestCategory.entries.flatMap { category ->
            DiagnosticRequestLabel.entries.map { label -> DiagnosticRequestStats(stage, category, label,
                attempts = MAX_DIAGNOSTIC_NUMBER, responseChars = MAX_DIAGNOSTIC_NUMBER,
                httpStatusCounts = (500..507).map { DiagnosticHttpStatusCount(it, MAX_DIAGNOSTIC_NUMBER) }) }
        } }.take(64)
        val json = ImportDiagnosticJson.encode(report().copy(requests = detail))
        assertTrue(json.toByteArray().size <= ImportDiagnosticJson.MAX_BYTES)
        assertTrue(JSONObject(json).getBoolean("metrics_bounded"))
        assertTrue(JSONObject(json).getJSONArray("requests").length() < 64)
        assertEquals("INCOMPLETE", JSONObject(json).getString("request_observation"))
    }
    @Test fun pcCountIsAvailableAfterObservedCaptureCompletes() {
        val collector = ImportDiagnosticCollector(DiagnosticImportMode.MANUAL_COOKIE, Clock())
        collector.beginImport()
        collector.measure(DiagnosticStage.PC_CAPTURE) { }
        val result = successful().copy(fetchedPlayCountCharts = 8)
        collector.result(result)
        assertEquals(8L, collector.finish(completed(result)).counters.playCountCharts)
    }
}
