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
}
