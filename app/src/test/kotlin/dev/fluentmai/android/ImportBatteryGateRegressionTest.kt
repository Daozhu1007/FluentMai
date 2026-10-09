package dev.fluentmai.android

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.vpn.core.LocalVpnService
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Real coordinator -> foreground-service admission with battery exemption explicitly absent.
 * No callback is consumed and the Cookie fixture never performs network or persistence work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportBatteryGateRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<ServiceController<BatteryImportFixtureService>>()
    private var generations = 0
    private lateinit var owner: QuickAuthCoordinator

    @Before fun setup() {
        ImportTaskStore.state.value = ImportTaskState()
        WahlapHookBridge.finishImport()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
        owner = QuickAuthCoordinator(scope, object : QuickAuthPorts {
            override fun currentTask() = ImportTaskStore.state.value
            override fun readiness() = WahlapHookBridge.captureReadiness.value
            override fun startCapture(requestId: Long, retry: ImportTaskState?, quick: Boolean) =
                ImportForegroundService.startHandoff(app, requestId, retry, quick)
            override fun cancelCapture(executionId: Long) = ImportForegroundService.cancelWaiting(app)
            override suspend fun generateAuthorize(executionId: Long): String {
                generations++
                return "https://example.invalid/synthetic-authorize"
            }
            override fun copyToClipboard(url: String) = Unit
            override fun captureFailed(executionId: Long, category: ImportFailureCategory) = Unit
        }, { 0L }, {})
        QuickAuthRuntime.coordinator = owner
    }

    @After fun cleanup() {
        controllers.forEach { it.destroy() }
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        QuickAuthRuntime.coordinator = null
        ImportTaskStore.state.value = ImportTaskState()
        WahlapHookBridge.finishImport()
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun takeServiceIntent(): Intent = shadowOf(app).nextStartedService.also { assertNotNull(it) }

    private fun deliverCapture(startId: Int = 1): ImportTaskState {
        val intent = takeServiceIntent()
        assertEquals(ImportForegroundService::class.java.name, intent.component?.className)
        val controller = Robolectric.buildService(BatteryImportFixtureService::class.java).create()
        controllers += controller
        controller.get().onStartCommand(intent, 0, startId)
        val task = ImportTaskStore.state.value
        assertEquals(ImportTaskPhase.Waiting, task.phase)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(8290))
        val pipeline = listOf(takeServiceIntent(), takeServiceIntent()).map { it.component?.className }
        assertTrue(pipeline.contains(WahlapHookHttpService::class.java.name))
        assertTrue(pipeline.contains(LocalVpnService::class.java.name))
        assertNoSettingsOrExemption()
        return task
    }

    private fun assertNoSettingsOrExemption() {
        assertNull("Import startup must not open system settings", shadowOf(app).nextStartedActivity)
        assertFalse(app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName))
        assertEquals("Startup must not generate OAuth before readiness", 0, generations)
    }

    @Test fun legacyCaptureStartsWithoutBatteryExemptionOrSettings() {
        assertNotNull(owner.request(quick = false, permissionRequired = false))
        deliverCapture()
    }

    @Test fun quickAuthStartsCaptureWithoutBatterySettingsAndRetainsReadinessGate() {
        owner.attachHost { true }
        assertNotNull(owner.request(quick = true, permissionRequired = false))
        owner.taskChanged(deliverCapture())
        assertEquals(QuickAuthPhase.WaitingCaptureReady, owner.state.value.phase)
        assertNoSettingsOrExemption()
    }

    @Test fun manualFreshAuthRetryStartsWithoutBatterySettings() = checkRetry(quick = false)

    @Test fun quickFreshAuthRetryStartsWithoutBatterySettings() = checkRetry(quick = true)

    private fun checkRetry(quick: Boolean) {
        ImportTaskStore.state.value = ImportTaskState(id = 17, executionId = 18,
            phase = ImportTaskPhase.AuthRetryAvailable, authAttempt = 1)
        assertNotNull(owner.request(quick, permissionRequired = false))
        val task = deliverCapture()
        assertEquals(17L, task.id)
        assertEquals(2, task.authAttempt)
        assertEquals(3, task.maxAuthAttempts)
        assertEquals(quick, task.quickAuth)
    }

    @Test fun cookieImportStartsForegroundWithoutBatterySettingsOrVpn() {
        ImportForegroundService.start(app, "synthetic-cookie")
        val intent = takeServiceIntent()
        val controller = Robolectric.buildService(BatteryImportFixtureService::class.java).create()
        controllers += controller
        controller.get().onStartCommand(intent, 0, 1)
        assertEquals(ImportTaskPhase.Running, ImportTaskStore.state.value.phase)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(8290))
        assertNull(shadowOf(app).nextStartedService)
        assertNull(intent.getStringExtra("input"))
        assertNoSettingsOrExemption()
    }

    @Test fun repeatedManualCaptureAttemptsNeverRequestBatteryExemption() {
        repeat(3) {
            assertNotNull(owner.request(quick = false, permissionRequired = false))
            val task = deliverCapture(it + 1)
            owner.taskChanged(task)
            owner.cancel()
            // Deliver natural teardown before the next independent start.
            controllers.last().destroy()
            controllers.removeAt(controllers.lastIndex)
            while (shadowOf(app).nextStartedService != null) { /* drain teardown requests */ }
            ImportTaskStore.state.value = ImportTaskState()
            WahlapHookBridge.finishImport()
            assertNoSettingsOrExemption()
        }
    }

    @Test fun vpnGrantStillGatesStartupWhenBatteryExemptionIsAbsent() {
        val request = owner.request(quick = false, permissionRequired = true)!!
        assertEquals(QuickAuthPhase.RequestingVpnPermission, owner.state.value.phase)
        assertNull(shadowOf(app).nextStartedService)
        owner.permissionResult(request + 1, true)
        assertNull(shadowOf(app).nextStartedService)
        owner.permissionResult(request, true)
        deliverCapture()
    }

    @Test fun vpnDenialStillPreventsStartupWithoutOpeningBatterySettings() {
        val request = owner.request(quick = true, permissionRequired = true)!!
        owner.permissionResult(request, false)
        assertEquals(QuickAuthPhase.Terminal, owner.state.value.phase)
        assertNull(shadowOf(app).nextStartedService)
        assertNoSettingsOrExemption()
    }

    @Test fun productionUiWiringCannotReintroduceAutomaticBatterySettings() {
        // Runtime admission tests above bypass Compose callbacks; audit the actual UI wiring too.
        val source = File("src/main/kotlin/dev/fluentmai/android/MainActivity.kt").readText()
        assertTrue(source.contains("val requestImportNotifications = rememberRequestImportNotifications()"))
        assertTrue(source.contains("onStartHookCapture = ::startHookCapture"))
        assertTrue(source.contains("onRetryAuthorization = ::startHookCapture"))
        assertTrue(source.contains("onImportWahlapCookie = ::startManualCookieImport"))
        assertTrue(source.contains("onQuickAuthorization = { if (!isPreparingHookLink) requestAuthorization(true) }"))
        val production = File("src/main").walkTopDown().filter { it.extension in setOf("kt", "java", "xml") }
        for (file in production) {
            val text = file.readText()
            for (forbidden in listOf("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
                "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "rememberRequestImportBackgroundAccess", "needsImportBatteryExemption")) {
                assertFalse("${file.path} must not contain $forbidden", text.contains(forbidden))
            }
        }
    }
}

class BatteryImportFixtureService : ImportForegroundService() {
    override suspend fun executeImport(input: String, cookie: Boolean,
        progress: (ImportProgress) -> Unit): RealWahlapImportResult = awaitCancellation()
}
