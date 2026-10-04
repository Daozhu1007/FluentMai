package dev.fluentmai.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import dev.fluentmai.android.core.database.FluentMaiDatabase
import dev.fluentmai.android.core.database.RoomImportPersistence
import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Url
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Real callback/Home HTTP, foreground-service transitions, importer and Room persistence.
 * Authorize entries are synthetic, never sent to WeChat/Wahlap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BoundedFreshAuthServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<FreshAuthFixtureService>
    private lateinit var server: WahlapLoopbackServer
    private lateinit var database: FluentMaiDatabase
    private val callbacks = AtomicInteger()
    private val scoreRequests = AtomicInteger()
    private val attempts = mutableListOf<WahlapOAuthAttempt>()
    private var rejectedAttempts = 1
    private var scoreFailures = 0

    @Before fun setup() {
        ImportTaskStore.state.value = ImportTaskState()
        WahlapHookBridge.finishImport()
        context.deleteDatabase("fluentmai-phase0.db")
        database = FluentMaiDatabase.create(context)
        val html = File("../fixtures/wahlap_valid_fixture.html").readText()
        server = WahlapLoopbackServer { request ->
            when (request.path) {
                "/wc_auth/oauth/callback/maimai-dx" -> {
                    callbacks.incrementAndGet()
                    WahlapLoopbackServer.Response(404)
                }
                "/maimai-mobile/home/" -> WahlapLoopbackServer.Response(body =
                    if (callbacks.get() <= rejectedAttempts) "<html>登录失败</html>" else "<html>authenticated fixture</html>")
                "/maimai-mobile/record/musicSort/search/" -> {
                    val count = scoreRequests.incrementAndGet()
                    WahlapLoopbackServer.Response(if (count <= scoreFailures) 503 else 200, html)
                }
                else -> WahlapLoopbackServer.Response()
            }
        }
        FreshAuthFixtureService.server = server
        FreshAuthFixtureService.database = database
        FreshAuthFixtureService.failure = null
        FreshAuthFixtureService.imports = 0
        FreshAuthFixtureService.scoreTimeouts = AtomicInteger()
        controller = Robolectric.buildService(FreshAuthFixtureService::class.java).create()
    }

    @After fun cleanup() {
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        WahlapHookBridge.finishImport()
        attempts.forEach { it.close() }
        server.close()
        database.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("State did not settle: ${ImportTaskStore.state.value}", condition())
    }

    private fun start() {
        controller.get().onStartCommand(Intent(context, FreshAuthFixtureService::class.java).setAction("wait_for_wechat"), 0, 1)
        assertEquals(ImportFlowState.AUTHORIZING, ImportTaskStore.state.value.flowState)
    }

    private fun authorize(number: Int): String {
        val redirect = "http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?r=fixture-r-$number&t=fixture-t-$number"
        WahlapAuthCaptureStore.authorize { attempt ->
            attempts += attempt
            "https://open.weixin.qq.com/connect/oauth2/authorize?redirect_uri=${URLEncoder.encode(redirect, "UTF-8")}&state=fixture-state-$number"
        }
        return "$redirect&state=fixture-state-$number&code=fixture-code-$number"
    }

    private fun capture(number: Int) {
        WahlapHookBridge.onAuthUrlCaptured(authorize(number))
        await { callbacks.get() >= number || ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
    }

    private fun retry(state: ImportTaskState, startId: Int = 2) {
        controller.get().onStartCommand(Intent(context, FreshAuthFixtureService::class.java)
            .setAction("retry_fresh_authorization").putExtra("logical_import_id", state.id)
            .putExtra("auth_attempt_number", state.authAttempt), 0, startId)
    }

    private fun batchCount(): Int = runBlocking(Dispatchers.IO) {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM import_batches").use {
            it.moveToFirst(); it.getInt(0)
        }
    }

    @Test fun rejectionDisposesAttemptThenUserRetryImportsAndPersistsExactlyOnce() {
        start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.AuthRetryAvailable }
        val failed = ImportTaskStore.state.value
        assertEquals(ImportFlowState.AUTH_RETRY_AVAILABLE, failed.flowState)
        assertEquals(ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED, failed.failureCategory)
        assertEquals(1, failed.authAttempt); assertEquals(3, failed.maxAuthAttempts)
        assertTrue(attempts.single().isClosed)
        assertEquals(0, batchCount()); assertEquals(0, FreshAuthFixtureService.imports)
        assertNull(WahlapAuthCaptureStore.captureCallback("http://fixture/stale", ""))
        // Real Android retry restarts a stopped service while retaining the logical import id.
        controller.destroy()
        controller = Robolectric.buildService(FreshAuthFixtureService::class.java).create()
        retry(failed)
        assertEquals(failed.id, ImportTaskStore.state.value.id)
        assertNotEquals(failed.executionId, ImportTaskStore.state.value.executionId)
        val nextCallback = authorize(2)
        assertNotSame(attempts[0], attempts[1])
        assertNotSame(attempts[0].httpClient, attempts[1].httpClient)
        WahlapHookBridge.onAuthUrlCaptured("http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?r=fixture-r-1&t=fixture-t-1&state=fixture-state-1&code=fixture-code-1")
        assertFalse(WahlapHookBridge.isImporting())
        assertEquals(1, callbacks.get())
        WahlapHookBridge.onAuthUrlCaptured(nextCallback)
        WahlapHookBridge.onAuthUrlCaptured(nextCallback)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        val done = ImportTaskStore.state.value
        assertEquals(ImportFlowState.COMPLETE, done.flowState)
        assertTrue(done.authenticated); assertNull(done.failureCategory)
        assertEquals(2, callbacks.get()); assertEquals(1, FreshAuthFixtureService.imports)
        assertEquals(1, batchCount())
        assertTrue(runBlocking { database.scoreRecordDao().count() } > 0)
        assertTrue(attempts.all { it.isClosed })
        assertTrue(WahlapAuthCaptureStore.consumeReplayState().isEmpty)
    }

    @Test fun threeRejectionsAreBoundedAndRepeatedRetryRequestsCannotCreateFourth() {
        rejectedAttempts = 3; start()
        for (number in 1..3) {
            capture(number)
            await { !ImportTaskStore.state.value.busy }
            val state = ImportTaskStore.state.value
            assertEquals(number, state.authAttempt)
            assertTrue(attempts.last().isClosed)
            if (number < 3) {
                assertEquals(ImportTaskPhase.AuthRetryAvailable, state.phase)
                retry(state, number + 1); retry(state, number + 2)
                assertEquals(number + 1, ImportTaskStore.state.value.authAttempt)
            } else {
                assertEquals(ImportFlowState.FAILED, state.flowState)
                retry(state, 7)
                assertEquals(state, ImportTaskStore.state.value)
            }
        }
        assertEquals(3, callbacks.get()); assertEquals(3, attempts.size)
        assertEquals(0, FreshAuthFixtureService.imports); assertEquals(0, batchCount())
        assertNull(WahlapAuthCaptureStore.captureCallback("http://fixture/stale", ""))
    }

    @Test fun callback404AndAuthenticatedHomeIsSuccessWithoutFreshRetry() {
        rejectedAttempts = 0; start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFlowState.COMPLETE, ImportTaskStore.state.value.flowState)
        assertEquals(1, callbacks.get()); assertEquals(1, attempts.size)
    }

    @Test fun scoreRetryAfterHomeDoesNotRestartOAuth() {
        rejectedAttempts = 0
        FreshAuthFixtureService.scoreTimeouts.set(1)
        start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFlowState.COMPLETE, ImportTaskStore.state.value.flowState)
        assertEquals(2, scoreRequests.get()) // Timeout before sending, then BASIC + ADVANCED
        assertEquals(-2, FreshAuthFixtureService.scoreTimeouts.get()) // Three attempts through the mapper
        assertEquals(1, callbacks.get()); assertEquals(1, attempts.size)
        assertEquals(1, batchCount())
    }

    @Test fun exhaustedScoreSourceAfterHomeIsPartialWithUsefulRoomPersistence() {
        rejectedAttempts = 0; scoreFailures = 3; start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFlowState.PARTIAL, ImportTaskStore.state.value.flowState)
        assertTrue(ImportTaskStore.state.value.authenticated)
        assertEquals(1, callbacks.get()); assertEquals(1, batchCount())
        assertTrue(runBlocking { database.scoreRecordDao().count() } > 0)
        assertNull(ImportTaskStore.state.value.failureCategory)
    }

    @Test fun callbackTransportFailureIsNotAnAuthRejection() {
        FreshAuthFixtureService.failure = IOException("fixture transport failure")
        start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFailureCategory.NETWORK_TRANSPORT, ImportTaskStore.state.value.failureCategory)
        assertTrue(attempts.single().isClosed); assertEquals(0, batchCount())
    }

    @Test fun cancellationPropagatesAndDoesNotOfferRetry() {
        FreshAuthFixtureService.failure = CancellationException("fixture cancellation")
        start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFailureCategory.CANCELLED, ImportTaskStore.state.value.failureCategory)
        assertEquals(1, attempts.size); assertTrue(attempts.single().isClosed)
    }

    @Test fun captureFailureAndAuthorizeFailureRemainDistinct() {
        for (category in listOf(ImportFailureCategory.CAPTURE_VPN, ImportFailureCategory.AUTHORIZE_GENERATION)) {
            start(); authorize(1)
            WahlapHookBridge.captureFailed(category)
            await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
            assertEquals(category, ImportTaskStore.state.value.failureCategory)
            assertTrue(attempts.last().isClosed)
            assertEquals(0, callbacks.get())
        }
    }

    @Test fun destroyingWaitingServiceDisposesPendingAuthorization() {
        start(); authorize(1); controller.destroy()
        await { !ImportTaskStore.state.value.busy }
        assertTrue(attempts.single().isClosed)
        assertEquals(ImportFailureCategory.CANCELLED, ImportTaskStore.state.value.failureCategory)
        assertNull(WahlapAuthCaptureStore.captureCallback("http://fixture/stale", ""))
    }

    @Test fun staleServiceDestructionFailureAndQueuedCallbackCannotAffectNextTransaction() {
        start(); capture(1)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.AuthRetryAvailable }
        val failed = ImportTaskStore.state.value
        val oldController = controller
        controller = Robolectric.buildService(FreshAuthFixtureService::class.java).create()
        retry(failed)
        val callback = authorize(2)
        oldController.destroy()
        WahlapHookBridge.captureFailed(ImportFailureCategory.AUTHORIZE_GENERATION, failed.executionId)
        WahlapHookBridge.capturedAuthUrls.tryEmit("http://fixture/stale-callback")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ImportTaskPhase.Waiting, ImportTaskStore.state.value.phase)
        assertFalse(attempts.last().isClosed)
        WahlapHookBridge.onAuthUrlCaptured(callback)
        await { ImportTaskStore.state.value.phase == ImportTaskPhase.Finished }
        assertEquals(ImportFlowState.COMPLETE, ImportTaskStore.state.value.flowState)
        assertEquals(1, batchCount())
    }
}

class FreshAuthFixtureService : ImportForegroundService() {
    override suspend fun executeImport(input: String, cookie: Boolean, progress: (ImportProgress) -> Unit): RealWahlapImportResult {
        WahlapHttpScorePageClient(PrivacyRedactor(), attempt = WahlapAuthCaptureStore.consumeAttempt(input),
            mapRequestUrl = {
                val path = Url(it).encodedPath
                if (path.contains("musicSort") && scoreTimeouts.getAndDecrement() > 0)
                    throw java.net.SocketTimeoutException("synthetic score timeout")
                server.url(path)
            },
            fetcher = WahlapResilientFetcher(delayFor = {})).use { client ->
            failure?.let { throw it }
            client.login(input)
            authenticatedHomeEstablished()
            imports++
            return RealWahlapImportAdapter(difficulties = listOf(Difficulty.BASIC, Difficulty.ADVANCED))
                .importFetchedPages("synthetic-fresh-auth-service", WahlapScorePageProvider(client::fetchScorePage), RoomImportPersistence(database))
        }
    }
    companion object {
        internal lateinit var server: WahlapLoopbackServer
        internal lateinit var database: FluentMaiDatabase
        internal var failure: Exception? = null
        internal var imports = 0
        internal lateinit var scoreTimeouts: AtomicInteger
    }
}
