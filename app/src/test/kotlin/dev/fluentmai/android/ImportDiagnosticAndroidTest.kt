package dev.fluentmai.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Url
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportDiagnosticAndroidTest {
    private fun report(outcome: DiagnosticOutcome = DiagnosticOutcome.COMPLETE) = ImportDiagnosticReport(
        appVersion = APP_VERSION, createdAtEpochMs = 1_700_000_000_000, mode = DiagnosticImportMode.MANUAL_COOKIE,
        outcome = outcome, termination = DiagnosticTermination.FINISHED, totalDurationMs = 120,
        executionDurationMs = 120, authorizationWaitDurationMs = null, unattributedDurationMs = 120,
        stages = DiagnosticStage.entries.map { DiagnosticStageTiming(it) }, requests = emptyList())
    private fun directory() = Files.createTempDirectory("import-observability-").toFile()
    private fun storeReport(store: ImportDiagnosticStore, execution: Long, report: ImportDiagnosticReport) {
        store.begin(execution, report.mode)
        assertTrue(store.finish(execution, report))
    }
    @Test fun latestTypedReportSurvivesActivityDestructionAndStoreRecreation() {
        val directory = directory()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val store = ImportDiagnosticStore(directory)
        storeReport(store, 1, report())
        activity.pause().stop().destroy()
        val recreated = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(DiagnosticOutcome.COMPLETE, ImportDiagnosticStore(directory).latest.value?.outcome)
        recreated.pause().stop().destroy()
        assertTrue(directory.listFiles()!!.all { it.length() <= ImportDiagnosticJson.MAX_BYTES })
    }
    @Test fun activeRunDoesNotReattributePreviousCompleteAndProcessDeathBecomesUnknownInterruption() {
        val directory = directory()
        val store = ImportDiagnosticStore(directory)
        storeReport(store, 1, report())
        store.begin(2, DiagnosticImportMode.WECHAT_OAUTH)
        // In-process the prior report remains explicitly available for separate display.
        assertEquals(DiagnosticImportMode.MANUAL_COOKIE, store.latest.value?.mode)
        val recovered = ImportDiagnosticStore(directory).latest.value!!
        assertEquals(DiagnosticTermination.INTERRUPTED, recovered.termination)
        assertNull(recovered.outcome); assertNull(recovered.totalDurationMs)
        assertTrue(recovered.stages.all { it.observation == DiagnosticObservation.UNOBSERVED })
        assertEquals(recovered, ImportDiagnosticStore(directory).latest.value)
    }
    @Test fun staleExecutionCannotReplaceNewerReportIncludingLateCompletion() {
        val store = ImportDiagnosticStore(directory())
        store.begin(1, DiagnosticImportMode.MANUAL_COOKIE)
        store.begin(2, DiagnosticImportMode.MANUAL_COOKIE)
        assertFalse(store.finish(1, report(DiagnosticOutcome.COMPLETE)))
        assertTrue(store.finish(2, report(DiagnosticOutcome.PARTIAL)))
        assertFalse(store.finish(1, report()))
        assertEquals(DiagnosticOutcome.PARTIAL, store.latest.value?.outcome)
    }
    @Test fun unfinishedAtomicReplacementKeepsPreviousAndOversizedOrCorruptSnapshotIsUnavailable() {
        val directory = directory()
        val store = ImportDiagnosticStore(directory)
        storeReport(store, 1, report())
        val base = File(directory, "import-diagnostic.json")
        File(directory, "import-diagnostic.json.new").writeText("partial write")
        assertEquals(DiagnosticOutcome.COMPLETE, ImportDiagnosticStore(directory).latest.value?.outcome)
        base.writeText("x".repeat(ImportDiagnosticJson.MAX_BYTES + 1))
        assertNull(ImportDiagnosticStore(directory).latest.value)
        base.writeText("not JSON")
        assertNull(ImportDiagnosticStore(directory).latest.value)
    }
    @Test fun unavailableReportDoesNotCreateExportSnapshot() {
        val store = ImportDiagnosticStore(directory())
        assertNull(store.prepareExport()); assertNull(store.pendingExport())
    }
    @Test fun completePartialFailedExportsWriteParseableSanitizedJsonWithoutStoragePermissions() {
        val context = RuntimeEnvironment.getApplication()
        DiagnosticOutcome.entries.forEach { outcome ->
            val output = ByteArrayOutputStream()
            val uri = Uri.parse("content://fixture/diagnostic/$outcome")
            shadowOf(context.contentResolver).registerOutputStream(uri, output)
            ImportDiagnosticExport.write(context, uri, report(outcome))
            val json = JSONObject(output.toString("UTF-8"))
            assertEquals(outcome.name, json.getString("outcome"))
            assertEquals(1, json.getInt("schema_version"))
            assertFalse(output.toString("UTF-8").contains("content://"))
            assertTrue(ImportDiagnosticExport.filename(report(outcome)).matches(Regex("FluentMai-import-diagnostic-\\d{8}-\\d{6}\\.json")))
        }
    }
    @Test fun pinnedExportSurvivesRecreationAndANewerReport() {
        val directory = directory()
        val store = ImportDiagnosticStore(directory)
        storeReport(store, 1, report(DiagnosticOutcome.PARTIAL))
        store.prepareExport()
        storeReport(store, 2, report(DiagnosticOutcome.COMPLETE))
        val newStore = ImportDiagnosticStore(directory)
        assertEquals(DiagnosticOutcome.PARTIAL, newStore.pendingExport()?.outcome)
        assertEquals(DiagnosticOutcome.COMPLETE, newStore.latest.value?.outcome)
        newStore.clearExport()
        assertNull(newStore.pendingExport())
    }
    @Test fun androidContractInvokesCreateDocumentJsonFlow() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ActivityResultContracts.CreateDocument("application/json").createIntent(context, ImportDiagnosticExport.filename(report()))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals(ImportDiagnosticExport.filename(report()), intent.getStringExtra(Intent.EXTRA_TITLE))
    }
    @Test fun pickerCallbackExportsPinnedReportAfterActualActivityRecreation() {
        val store = ImportDiagnosticStore(directory())
        storeReport(store, 1, report(DiagnosticOutcome.PARTIAL))
        DiagnosticExportTestActivity.store = store
        val controller = Robolectric.buildActivity(DiagnosticExportTestActivity::class.java).setup().visible()
        val activity = controller.get()
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(condition())
        }
        await { activity.export != null }
        activity.export!!.invoke()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val launched = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.action)
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        storeReport(store, 2, report(DiagnosticOutcome.COMPLETE))
        val recreated = Robolectric.buildActivity(DiagnosticExportTestActivity::class.java).create(saved).start().resume().visible()
        await { recreated.get().export != null }
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://fixture/recreated-export")
        shadowOf(recreated.get().contentResolver).registerOutputStream(uri, output)
        recreated.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { output.size() > 0 && store.pendingExport() == null }
        assertEquals("PARTIAL", JSONObject(output.toString("UTF-8")).getString("outcome"))
        recreated.pause().stop().destroy()
    }
    @Test fun bothHttpClientsFeedRealRetryObserverWithoutAnyExternalServices() = runBlocking {
        val html = File("../fixtures/wahlap_valid_fixture.html").readText()
        listOf(false, true).forEach { manual ->
            var calls = 0
            WahlapLoopbackServer { _ ->
                calls++
                WahlapLoopbackServer.Response(if (calls == 1) 503 else 200, html)
            }.use { server ->
                val collector = ImportDiagnosticCollector(if (manual) DiagnosticImportMode.MANUAL_COOKIE else DiagnosticImportMode.WECHAT_OAUTH)
                collector.beginImport()
                val attempt: (WahlapRequestCategory, DiagnosticRequestLabel, WahlapAttemptLog) -> Unit = collector::attempt
                collector.measure(DiagnosticStage.MASTER) {
                    if (manual) WahlapManualCookieScorePageClient(WahlapCookieImportCredentials.parse("_t=synthetic; userId=synthetic"), PrivacyRedactor(),
                        mapRequestUrl = { server.url(Url(it).encodedPath) }, fetcher = WahlapResilientFetcher(delayFor = {}), onRequestAttempt = attempt).use { it.fetchScorePage(Difficulty.MASTER) }
                    else WahlapHttpScorePageClient(PrivacyRedactor(), attempt = WahlapOAuthAttempt(),
                        mapRequestUrl = { server.url(Url(it).encodedPath) }, fetcher = WahlapResilientFetcher(delayFor = {}), onRequestAttempt = attempt).use { it.fetchScorePage(Difficulty.MASTER) }
                }
                val report = collector.finish(ImportTaskState(phase = ImportTaskPhase.Finished))
                assertEquals(2L, report.requests.single().attempts)
                assertEquals(1L, report.requests.single().retries)
                assertEquals(DiagnosticRequestLabel.MASTER, report.requests.single().label)
                val json = ImportDiagnosticJson.encode(report)
                assertFalse(json.contains("127.0.0.1")); assertFalse(json.contains("synthetic")); assertFalse(json.contains("<html"))
            }
        }
    }
}

class DiagnosticExportTestActivity : ComponentActivity() {
    var export: (() -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { export = rememberExportImportDiagnostic(this, store) }
    }
    companion object { internal lateinit var store: ImportDiagnosticStore }
}
