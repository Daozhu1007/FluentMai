package dev.fluentmai.android

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapImportRunnerTest {
    @After fun cleanup() = WahlapAuthCaptureStore.discardPendingAttempt()

    @Test fun callbackTransportFailureClosesConsumedAttemptInRealRunner() = runBlocking {
        verifyLoginFailureClosesAttempt(afterLogin = {})
    }

    @Test fun shutdownCallbackFailureStillClosesConsumedAttemptInRealRunner() = runBlocking {
        verifyLoginFailureClosesAttempt(afterLogin = { throw IOException("fixture shutdown failure") })
    }

    private suspend fun verifyLoginFailureClosesAttempt(afterLogin: () -> Unit) {
        // Closing the socket without any HTTP response deterministically fails callback transport.
        WahlapLoopbackServer { throw IOException("fixture closed connection") }.use { server ->
            val attempt = WahlapAuthCaptureStore.beginAttempt()
            val callback = server.url("/callback/maimai-dx?code=fixture-only")
            WahlapAuthCaptureStore.captureCallback(callback, "")
            WahlapImportRunner(RuntimeEnvironment.getApplication()).use { runner ->
                try { runner.runRealImport(callback, afterLogin); fail("expected callback transport failure") }
                catch (_: ImportDiagnosticException) { }
            }
            assertTrue(attempt.isClosed)
            assertTrue(WahlapAuthCaptureStore.consumeReplayState().isEmpty)
            assertEquals(1, server.requestsAt("/callback/maimai-dx").size)
        }
    }
}
