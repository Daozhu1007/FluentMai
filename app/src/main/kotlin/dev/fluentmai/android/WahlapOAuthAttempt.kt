package dev.fluentmai.android

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One session from authorize through callback, home and import; never reused by another attempt. */
class WahlapOAuthAttempt(
    val httpClient: WahlapImportHttpClient = WahlapImportHttpClient(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    internal fun capture(state: WahlapAuthReplayState) {
        check(!isClosed) { "OAuth attempt is closed" }
        httpClient.attachAuthReplay(state)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) httpClient.close()
    }
}
