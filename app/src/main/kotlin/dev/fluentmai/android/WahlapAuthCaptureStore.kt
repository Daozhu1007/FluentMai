package dev.fluentmai.android

import java.util.Locale

/** Browser context; Cookie is seeded into the attempt jar rather than replayed as a header. */
data class WahlapAuthReplayState(
    val headers: Map<String, String> = emptyMap(),
    val pendingAuthCookies: String? = null,
) {
    val isEmpty: Boolean get() = headers.isEmpty() && pendingAuthCookies == null
}

/**
 * Synchronized handoff from hook authorize to VPN capture to the import service. Owns only the
 * unfinished attempt: consumption transfers ownership without replacing its client or jar.
 * Closing capture services cannot close an already-consumed import session. Never logs raw state.
 */
open class WahlapAuthCaptureHandoff(
    private val attemptFactory: () -> WahlapOAuthAttempt = { WahlapOAuthAttempt() },
) {
    private var pendingAttempt: WahlapOAuthAttempt? = null
    private var capturedUrl: String? = null
    private var replayState = WahlapAuthReplayState()

    @Synchronized
    fun beginAttempt(): WahlapOAuthAttempt {
        discardPendingAttempt()
        return attemptFactory().also { pendingAttempt = it }
    }

    @Synchronized
    fun isCurrent(attempt: WahlapOAuthAttempt): Boolean = pendingAttempt === attempt && !attempt.isClosed

    /** Null means no current authorize attempt, or its callback was already captured. */
    @Synchronized
    fun captureCallback(authUrl: String, rawRequestHeaders: String): Int? {
        val attempt = pendingAttempt?.takeUnless { it.isClosed } ?: return null
        if (capturedUrl != null) return null
        val count = storeReplayHeaders(rawRequestHeaders)
        attempt.capture(replayState)
        capturedUrl = authUrl.trim()
        return count
    }

    /** Match the captured callback before transferring ownership; never replay a later attempt. */
    @Synchronized
    fun consumeAttempt(authUrl: String): WahlapOAuthAttempt {
        check(capturedUrl == authUrl.trim()) { "No matching captured OAuth attempt" }
        val attempt = checkNotNull(pendingAttempt) { "OAuth attempt already consumed" }
        check(!attempt.isClosed) { "OAuth attempt is closed" }
        pendingAttempt = null
        capturedUrl = null
        clear()
        return attempt
    }

    @Synchronized
    fun discardPendingAttempt(attempt: WahlapOAuthAttempt? = pendingAttempt) {
        if (attempt !== pendingAttempt) return
        pendingAttempt = null
        capturedUrl = null
        clear()
        attempt?.close()
    }

    @Synchronized
    fun storeReplayHeaders(rawRequestHeaders: String): Int {
        val headers = linkedMapOf<String, String>()
        var cookies: String? = null
        rawRequestHeaders.lineSequence().drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isBlank() || value.isBlank()) return@forEach
            when (name.lowercase(Locale.ROOT)) {
                "cookie" -> cookies = value
                in SKIPPED_REPLAY_HEADERS -> Unit
                else -> headers[name] = value
            }
        }
        replayState = WahlapAuthReplayState(headers.toMap(), cookies)
        return headers.size
    }

    @Synchronized
    fun replayHeaderSnapshot(): Map<String, String> = replayState.headers.toMap()

    /** Consuming browser context leaves authorize cookies in the existing attempt jar intact. */
    @Synchronized
    fun consumeReplayState(): WahlapAuthReplayState = replayState.also { clear() }

    @Synchronized
    fun clear() {
        replayState = WahlapAuthReplayState()
    }

    private companion object {
        val SKIPPED_REPLAY_HEADERS = setOf("host", "connection", "content-length", "proxy-connection")
    }
}

object WahlapAuthCaptureStore : WahlapAuthCaptureHandoff()
