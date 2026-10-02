package dev.fluentmai.android

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Captured replay state of exactly one OAuth callback request: the request headers to replay
 * (minus hop-by-hop headers) and the browser's Cookie header, which is seeded into the import
 * client's cookie storage when the callback URL is requested.
 */
data class WahlapAuthReplayState(
    val headers: Map<String, String> = emptyMap(),
    val pendingAuthCookies: String? = null,
) {
    val isEmpty: Boolean get() = headers.isEmpty() && pendingAuthCookies == null
}

/**
 * Process-wide handoff for the request captured by the VPN tunnel when the WeChat browser hit
 * the OAuth callback. The import client consumes the state for exactly one request — the
 * AUTH_CALLBACK GET — because the callback URL carries a single-use code bound to the browser's
 * session context.
 *
 * The store never logs and never returns header values outside the client replay path. Every
 * capture clears and refills the map, so headers from an older capture can never leak into a
 * newer import, and consuming clears the store so no credentials remain available for a later
 * import.
 */
object WahlapAuthCaptureStore {
    private val replayHeaders = ConcurrentHashMap<String, String>()
    private val pendingAuthCookies = AtomicReference<String?>(null)

    fun storeReplayHeaders(rawRequestHeaders: String): Int {
        clear()
        rawRequestHeaders
            .lineSequence()
            .drop(1)
            .forEach { line ->
                if (line.isBlank()) return@forEach
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isBlank() || value.isBlank()) return@forEach
                val normalized = name.lowercase(Locale.ROOT)
                if (normalized == "cookie") {
                    pendingAuthCookies.set(value)
                    return@forEach
                }
                if (normalized in SKIPPED_REPLAY_HEADERS) return@forEach
                replayHeaders[name] = value
            }
        return replayHeaders.size
    }

    fun replayHeaderSnapshot(): Map<String, String> = replayHeaders.toMap()

    /**
     * Takes the captured state of the current authorization attempt and clears the store, so a
     * later import can never replay credentials that belong to this one.
     */
    fun consumeReplayState(): WahlapAuthReplayState {
        val headers = replayHeaders.toMap()
        val cookies = pendingAuthCookies.getAndSet(null)
        replayHeaders.clear()
        return WahlapAuthReplayState(headers = headers, pendingAuthCookies = cookies)
    }

    fun clear() {
        replayHeaders.clear()
        pendingAuthCookies.set(null)
    }

    private val SKIPPED_REPLAY_HEADERS = setOf(
        "host",
        "connection",
        "content-length",
        "proxy-connection",
    )
}
