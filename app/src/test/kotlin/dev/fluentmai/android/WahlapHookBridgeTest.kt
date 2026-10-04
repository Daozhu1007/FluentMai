package dev.fluentmai.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapHookBridgeTest {
    @Before fun setup() = WahlapHookBridge.finishImport()
    @After fun cleanup() = WahlapHookBridge.finishImport()

    @Test fun vpnCallbackHandsTheAuthorizeAttemptToImporterExactlyOnce() = runBlocking {
        val attempt = WahlapAuthCaptureStore.beginAttempt()
        val callback = "http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?code=fixture-only"
        val emission = async(start = CoroutineStart.UNDISPATCHED) { WahlapHookBridge.capturedAuthUrls.first() }
        try {
            WahlapHookBridge.onAuthRequestCaptured(callback, "GET / HTTP/1.1\r\nUser-Agent: Fixture-WeChat\r\n")
            assertEquals(callback, withTimeout(1_000) { emission.await() })
            assertTrue(WahlapHookBridge.isImporting())
            assertSame(attempt, WahlapAuthCaptureStore.consumeAttempt(callback))
            WahlapHookBridge.onAuthRequestCaptured(callback, "")
            assertTrue(WahlapAuthCaptureStore.consumeReplayState().isEmpty)
            WahlapHookBridge.finishImport()
            assertFalse("Capture shutdown cannot close an import-owned attempt", attempt.isClosed)
        } finally { emission.cancel(); attempt.close() }
    }

    @Test fun callbackWithoutAnAuthorizeAttemptCannotStartAnImport() {
        WahlapHookBridge.onAuthUrlCaptured("http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?code=fixture-only")
        assertFalse(WahlapHookBridge.isImporting())
        assertTrue(WahlapAuthCaptureStore.consumeReplayState().isEmpty)
    }

    @Test fun staleCallbackCannotSuppressConcurrentCurrentCallback() = runBlocking {
        WahlapAuthCaptureStore.authorize {
            "https://open.weixin.qq.com/connect/oauth2/authorize?redirect_uri=" +
                "http%3A%2F%2Ftgk-wcaime.wahlap.com%2Fwc_auth%2Foauth%2Fcallback%2Fmaimai-dx%3Fr%3Dfixture-current%26t%3Dfixture-now&state=fixture-current"
        }
        val current = "http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?r=fixture-current&t=fixture-now&state=fixture-current&code=fixture-only"
        val stale = current.replace("fixture-current", "fixture-old")
        val emitted = async(start = CoroutineStart.UNDISPATCHED) { WahlapHookBridge.capturedAuthUrls.first() }
        val validStarted = java.util.concurrent.CountDownLatch(1)
        val validFinished = java.util.concurrent.CountDownLatch(1)
        val invalidThread = Thread { WahlapHookBridge.onAuthUrlCaptured(stale) }
        val validThread = Thread {
            validStarted.countDown()
            WahlapHookBridge.onAuthUrlCaptured(current)
            validFinished.countDown()
        }
        try {
            // Hold the handoff while the stale dispatch has claimed the bridge flag. The valid
            // dispatch must wait for rejection, rather than lose its single-use callback.
            synchronized(WahlapAuthCaptureStore) {
                invalidThread.start()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (!WahlapHookBridge.isImporting() && System.nanoTime() < deadline) Thread.yield()
                assertTrue(WahlapHookBridge.isImporting())
                validThread.start()
                assertTrue(validStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(validFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            }
            invalidThread.join(2_000); validThread.join(2_000)
            assertFalse(invalidThread.isAlive); assertFalse(validThread.isAlive)
            assertEquals(current, withTimeout(1_000) { emitted.await() })
            assertTrue(WahlapAuthCaptureStore.isCapturedCallback(current))
        } finally {
            emitted.cancel()
            invalidThread.join(2_000); if (validThread.state != Thread.State.NEW) validThread.join(2_000)
        }
    }
}
