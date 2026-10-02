package dev.fluentmai.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture store is the handoff between the VPN tunnel thread and the import client. Each
 * capture must fully replace the previous one so credentials of an older OAuth attempt can never
 * be replayed into a newer import, and consuming must leave nothing behind for a later import.
 */
class WahlapAuthCaptureStoreTest {
    @Test
    fun capturedRequestHeadersAreStoredForReplay() {
        val raw = """
            GET /wc_auth/oauth/callback/maimai-dx?code=abc HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            Cookie: wgSession=secret-session
            User-Agent: Mozilla/5.0
        """.trimIndent()

        val count = WahlapAuthCaptureStore.storeReplayHeaders(raw)
        val snapshot = WahlapAuthCaptureStore.replayHeaderSnapshot()

        assertEquals(1, count)
        assertEquals("Mozilla/5.0", snapshot["User-Agent"])
        // The captured Cookie header is held separately for cookie-jar seeding, not replayed raw.
        assertFalse(snapshot.containsKey("Cookie"))
        // host is deliberately never replayed.
        assertFalse(snapshot.containsKey("Host"))
    }

    @Test
    fun capturedCookieHeaderIsHeldAsPendingAuthCookies() {
        WahlapAuthCaptureStore.storeReplayHeaders(
            """
            GET /wc_auth/oauth/callback/maimai-dx?code=abc HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            Cookie: wgSession=secret-session; userId=42
        """.trimIndent(),
        )

        val state = WahlapAuthCaptureStore.consumeReplayState()
        assertEquals("wgSession=secret-session; userId=42", state.pendingAuthCookies)
        assertFalse(state.isEmpty)
    }

    @Test
    fun aNewCaptureFullyReplacesThePreviousOne() {
        WahlapAuthCaptureStore.storeReplayHeaders(
            """
            GET /wc_auth/oauth/callback/maimai-dx?code=old HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            Cookie: old-session
            User-Agent: Old-Browser
        """.trimIndent(),
        )
        WahlapAuthCaptureStore.storeReplayHeaders(
            """
            GET /wc_auth/oauth/callback/maimai-dx?code=new HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            User-Agent: WeChat-Browser
        """.trimIndent(),
        )

        val state = WahlapAuthCaptureStore.consumeReplayState()
        assertEquals("WeChat-Browser", state.headers["User-Agent"])
        assertTrue(state.headers.containsKey("User-Agent"))
        assertNull("Stale pending cookies from the previous capture must be dropped", state.pendingAuthCookies)
    }

    @Test
    fun consumingTheStoreLeavesNoCredentialsForALaterImport() {
        WahlapAuthCaptureStore.storeReplayHeaders(
            """
            GET /wc_auth/oauth/callback/maimai-dx?code=abc HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            Cookie: wgSession=secret-session
            User-Agent: WeChat-Browser
        """.trimIndent(),
        )

        val first = WahlapAuthCaptureStore.consumeReplayState()
        assertEquals("WeChat-Browser", first.headers["User-Agent"])
        assertEquals("wgSession=secret-session", first.pendingAuthCookies)

        val second = WahlapAuthCaptureStore.consumeReplayState()
        assertTrue("No captured state may remain after consumption", second.isEmpty)
        assertNull(second.pendingAuthCookies)
    }

    @Test
    fun clearRemovesEverything() {
        WahlapAuthCaptureStore.storeReplayHeaders(
            """
            GET /wc_auth/oauth/callback/maimai-dx?code=abc HTTP/1.1
            Host: tgk-wcaime.wahlap.com
            Cookie: wgSession=secret-session
            User-Agent: WeChat-Browser
        """.trimIndent(),
        )

        WahlapAuthCaptureStore.clear()

        assertTrue(WahlapAuthCaptureStore.consumeReplayState().isEmpty)
    }
}
