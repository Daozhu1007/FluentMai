package dev.fluentmai.android

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.importer.WahlapDifficultyFailure
import dev.fluentmai.android.core.importer.WahlapImportOutcome
import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Url
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportForegroundServiceTest {
    private lateinit var controller: ServiceController<FakeImportService>
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        ImportTaskStore.state.value = ImportTaskState()
        FakeImportService.completion = CompletableDeferred()
        FakeImportService.starts = 0
        FakeImportService.failPage = false
        FakeImportService.advancePages = false
        WahlapHookBridge.finishImport()
        controller = Robolectric.buildService(FakeImportService::class.java).create()
    }

    @After fun cleanup() {
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        manager.cancelAll()
    }

    private fun cookieIntent(): Intent {
        ImportForegroundService.start(context, "synthetic-cookie")
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Service state did not settle: ${ImportTaskStore.state.value}", condition())
    }

    @Test fun cookieImportSurvivesActivityDestructionAndPostsCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val intent = cookieIntent()
        controller.get().onStartCommand(intent, 0, 1)
        assertNull("Credential must be removed from start intent", intent.getStringExtra("input"))
        await { ImportTaskStore.state.value.progress?.processedPages == 2 }
        activity.pause().stop().destroy()
        assertEquals(ImportTaskPhase.Running, ImportTaskStore.state.value.phase)
        val running = shadowOf(manager).getNotification(8290)
        assertNotNull(running)
        assertTrue(running.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // Repeated UI/notification requests cannot start a second import.
        controller.get().onStartCommand(cookieIntent(), 0, 2)
        assertEquals(1, FakeImportService.starts)
        FakeImportService.completion.complete(success())
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertTrue(ImportTaskStore.state.value.complete)
        val finished = shadowOf(manager).getNotification(8290)
        assertEquals(Notification.VISIBILITY_PUBLIC, finished.visibility)
        assertEquals("导入完成", finished.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, finished.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(shadowOf(finished.contentIntent).savedIntent.getBooleanExtra(ImportForegroundService.OPEN_IMPORT, false))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        val shutdown = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(dev.fluentmai.android.vpn.core.LocalVpnService.DISCONNECT_INTENT, shutdown.action)
        assertEquals(dev.fluentmai.android.vpn.core.LocalVpnService::class.java.name, shutdown.component?.className)
    }

    @Test fun capturesAuthorizationWithoutAnyActivityCollector() {
        ImportForegroundService.start(context)
        controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
        assertEquals(ImportTaskPhase.Waiting, ImportTaskStore.state.value.phase)
        WahlapHookBridge.capturedAuthUrls.tryEmit("synthetic-authorization")
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.complete(success().copy(activityWarnings = listOf("部分 PC 未读取")))
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertFalse(ImportTaskStore.state.value.complete)
        assertTrue(ImportTaskStore.state.value.succeeded)
        assertEquals("成绩已导入，部分数据未完整同步", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun partialImportWithPersistedDifficultiesCountsAsSucceeded() {
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.complete(
            success().copy(
                importResult = ImportResult("test", 12, 0, 0, 0, 1),
                failedDifficultyCount = 1,
                failures = listOf(WahlapDifficultyFailure(Difficulty.MASTER, "HTTP 503")),
                outcome = WahlapImportOutcome.PARTIAL,
            ),
        )
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertTrue(ImportTaskStore.state.value.succeeded)
        assertFalse(ImportTaskStore.state.value.complete)
        assertEquals("成绩已导入，部分数据未完整同步", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    private fun perPageSupplementalResult(useful: Boolean): RealWahlapImportResult = runBlocking {
        val html = if (useful) File("../fixtures/wahlap_valid_fixture.html").readText() else
            """<html><body><form action="/maimai-mobile/record/musicSort/search/">
                <input name="diff" value="0"><input name="sort" value="1"></form>
                <div>没有符合条件的乐曲。</div></body></html>"""
        WahlapLoopbackServer { request ->
            WahlapLoopbackServer.Response(if (request.path.contains("ratingTargetMusic")) 503 else 200, html)
        }.use { server ->
            WahlapHttpScorePageClient(PrivacyRedactor(), attempt = WahlapOAuthAttempt(),
                mapRequestUrl = { server.url(Url(it).encodedPath) }, fetcher = WahlapResilientFetcher(delayFor = {})).use { client ->
                RealWahlapImportAdapter(difficulties = listOf(Difficulty.BASIC)).importFetchedPages(
                    "fixture-service", WahlapScorePageProvider(client::fetchScorePage), BoundaryPersistence(),
                    WahlapSupplementalPageProvider(client::fetchSupplementalScorePages))
            }
        }
    }

    @Test fun realPerPageSupplementalFailureProducesPartialNotificationAndTaskState() {
        val result = perPageSupplementalResult(useful = true)
        assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
        assertEquals("rating-target-music", result.supplementalFailures.single().label)
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.complete(result)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertTrue(ImportTaskStore.state.value.succeeded)
        assertFalse(ImportTaskStore.state.value.complete)
        assertEquals(result.supplementalFailures, ImportTaskStore.state.value.result?.supplementalFailures)
        assertEquals("成绩已导入，部分数据未完整同步", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun realSupplementalFailureWithEmptyScoresProducesFailedNotification() {
        val result = perPageSupplementalResult(useful = false)
        assertEquals(WahlapImportOutcome.FAILED, result.outcome)
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.complete(result)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertFalse(ImportTaskStore.state.value.succeeded)
        assertFalse(ImportTaskStore.state.value.complete)
        assertEquals("导入未完成", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun cancellingAuthorizationWaitClosesItsUnfinishedAttempt() {
        controller.get().onStartCommand(Intent(context, FakeImportService::class.java).setAction("wait_for_wechat"), 0, 1)
        val attempt = WahlapAuthCaptureStore.beginAttempt()
        controller.get().onStartCommand(Intent(context, FakeImportService::class.java).setAction("cancel_import_wait"), 0, 2)
        // The handoff is also discarded directly during service shutdown, before Android destroys Hook.
        assertTrue(attempt.isClosed)
        assertFalse(ImportTaskStore.state.value.busy)
    }

    @Test fun failedOutcomeNeverCountsAsSucceededEvenWhenSomePagesWereFetched() {
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.complete(
            success().copy(
                failedDifficultyCount = 5,
                failures = Difficulty.entries.map { WahlapDifficultyFailure(it, "HTTP 503") },
                outcome = WahlapImportOutcome.FAILED,
            ),
        )
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertFalse(ImportTaskStore.state.value.succeeded)
        assertFalse(ImportTaskStore.state.value.complete)
        assertEquals("导入未完成", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun waitingCanBeCancelledAndNeverRestartsAutomatically() {
        assertEquals(android.app.Service.START_NOT_STICKY,
            controller.get().onStartCommand(Intent(context, FakeImportService::class.java).setAction("wait_for_wechat"), 0, 1))
        controller.get().onStartCommand(Intent(context, FakeImportService::class.java).setAction("cancel_import_wait"), 0, 2)
        assertFalse(ImportTaskStore.state.value.busy)
        assertNull(shadowOf(manager).getNotification(8290))
        assertEquals(0, FakeImportService.starts)
    }

    @Test fun failuresAreRedactedAndStopForegroundNotification() {
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        FakeImportService.completion.completeExceptionally(IllegalStateException("token=secret-value network failed"))
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertFalse(ImportTaskStore.state.value.complete)
        assertFalse(ImportTaskStore.state.value.error.orEmpty().contains("secret-value"))
        assertEquals("导入未完成", shadowOf(manager).getNotification(8290).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun failedPagesNeverShowCompleteEvenIfScoresImported() {
        FakeImportService.failPage = true
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { ImportTaskStore.state.value.pageFailed }
        FakeImportService.completion.complete(success())
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertTrue(ImportTaskStore.state.value.succeeded)
        assertFalse(ImportTaskStore.state.value.complete)
        assertEquals(ImportPageState.Failed, ImportTaskStore.state.value.progress?.pageState)
    }

    @Test fun destroyingServiceCancelsImportAndReleasesWakeLock() {
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        controller.destroy()
        await { !lock.isHeld }
        assertFalse(ImportTaskStore.state.value.busy)
        assertFalse(ImportTaskStore.state.value.complete)
    }

    @Test @Config(sdk = [34]) fun android14DeclaresDataSyncServiceAndCanWaitForAuthorization() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, ImportForegroundService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        controller.get().onStartCommand(Intent(context, FakeImportService::class.java).setAction("wait_for_wechat"), 0, 1)
        assertEquals(ImportTaskPhase.Waiting, ImportTaskStore.state.value.phase)
        assertNotNull(shadowOf(manager).getNotification(8290))
    }

    private fun success() = RealWahlapImportResult(ImportResult("test", 0, 0, 0, 0, 0), 1, 5, 0, emptyList())

    @Test fun workerContinuesAdvancingPagesWhileActivityIsStopped() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        FakeImportService.advancePages = true
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { FakeImportService.starts == 1 }
        activity.pause().stop()
        await { ImportTaskStore.state.value.progress?.processedPages == 5 }
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertNotNull(shadowOf(manager).getNotification(8290))
        FakeImportService.completion.complete(success())
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertFalse(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        activity.destroy()
    }

    @Test fun lockedPhoneReceivesPublicPageProgressWithoutCredentials() {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)
        assertTrue(keyguard.isKeyguardLocked)
        controller.get().onStartCommand(cookieIntent(), 0, 1)
        await { ImportTaskStore.state.value.progress?.processedPages == 2 }
        // Allow notification throttling to elapse, then emit a fresh page update.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        FakeImportService.reportProgress(ImportProgress(ImportStage.PlayCounts, "读取 PC", 3, 10, pageName = "测试谱面"))
        shadowOf(Looper.getMainLooper()).idle()
        val notification = shadowOf(manager).getNotification(8290)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(300, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("3/10"))
        assertTrue(text.contains("30%"))
        assertFalse(text.contains("synthetic-cookie"))
        assertTrue(keyguard.isKeyguardLocked)
    }
}

class FakeImportService : ImportForegroundService() {
    override suspend fun executeImport(input: String, cookie: Boolean, progress: (ImportProgress) -> Unit): RealWahlapImportResult {
        starts++
        reportProgress = progress
        progress(ImportProgress(ImportStage.PlayCounts, "读取 PC", 2, 10, failedPages = if (failPage) 1 else 0, pageName = "测试谱面"))
        if (advancePages) for (page in 3..5) {
            kotlinx.coroutines.delay(100)
            progress(ImportProgress(ImportStage.PlayCounts, "读取 PC", page, 10, pageName = "后台测试谱面"))
        }
        return completion.await()
    }
    companion object {
        lateinit var completion: CompletableDeferred<RealWahlapImportResult>
        @Volatile var starts = 0
        @Volatile var failPage = false
        @Volatile var advancePages = false
        lateinit var reportProgress: (ImportProgress) -> Unit
    }
}
