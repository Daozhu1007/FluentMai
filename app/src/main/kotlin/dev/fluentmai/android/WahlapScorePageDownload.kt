package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapAttemptMeta
import dev.fluentmai.android.core.importer.WahlapNonRetryableException
import dev.fluentmai.android.core.importer.WahlapTimeoutProfile
import dev.fluentmai.android.core.model.DiagnosticDownloadFailure
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

internal const val MAX_SCORE_BODY_BYTES = 8 * 1024 * 1024

internal class WahlapScoreDownloadException(val failure: DiagnosticDownloadFailure) :
    IOException("Score download failed: ${failure.name.lowercase()}")

private class WahlapScoreBodyTooLargeException : WahlapNonRetryableException("Score body exceeds size limit")

/**
 * execute(block) skips Ktor's default in-memory response saving and owns connection cleanup.
 * The start watchdog ends at the first nonempty body read (headers alone are not progress).
 * After that, each read has a fresh idle window; there is deliberately no total download timer.
 */
internal suspend fun HttpStatement.downloadScorePage(
    profile: WahlapTimeoutProfile,
    meta: WahlapAttemptMeta,
    maxBodyBytes: Int = MAX_SCORE_BODY_BYTES,
): WahlapPageResponse {
    val startMs = requireNotNull(profile.responseStartTimeoutMs)
    val idleMs = requireNotNull(profile.readIdleTimeoutMs)
    require(startMs > 0 && idleMs > 0 && maxBodyBytes > 0)
    meta.responseBytes = 0
    meta.progressChunks = 0
    fun failure(reason: DiagnosticDownloadFailure): Nothing {
        meta.downloadFailure = reason
        if (reason == DiagnosticDownloadFailure.BODY_TOO_LARGE) throw WahlapScoreBodyTooLargeException()
        throw WahlapScoreDownloadException(reason)
    }
    try {
        return coroutineScope {
            // Includes connection/DNS/TLS/redirect/header waiting, where no body progress is visible.
            // CIO independently enforces the shorter TCP connect timeout.
            val startWatchdog = launch {
                delay(startMs)
                failure(DiagnosticDownloadFailure.RESPONSE_START_TIMEOUT)
            }
            try {
                execute { response ->
                    try {
                        meta.httpStatus = response.status.value
                        // With Transfer-Encoding, Content-Length is not the framing length.
                        val expectedBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                            ?.takeIf { it >= 0 && response.headers[HttpHeaders.TransferEncoding] == null }
                        if (expectedBytes != null && expectedBytes > maxBodyBytes) {
                            failure(DiagnosticDownloadFailure.BODY_TOO_LARGE)
                        }
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(32 * 1024)
                        val body = ByteArrayOutputStream()
                        var received = 0L
                        try {
                            while (true) {
                                coroutineContext.ensureActive()
                                // A zero-byte result must never reset a deadline or spin indefinitely.
                                suspend fun nextChunk(): Int {
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val count = channel.readAvailable(buffer)
                                        if (count != 0) return count
                                        yield()
                                    }
                                }
                                val count = if (received == 0L) nextChunk() else {
                                    withTimeoutOrNull(idleMs) { nextChunk() }
                                        ?: failure(DiagnosticDownloadFailure.READ_IDLE_TIMEOUT)
                                }
                                if (count < 0) break
                                startWatchdog.cancel()
                                received += count
                                meta.responseBytes = received
                                meta.progressChunks = (meta.progressChunks ?: 0) + 1
                                if (received > maxBodyBytes) failure(DiagnosticDownloadFailure.BODY_TOO_LARGE)
                                body.write(buffer, 0, count)
                            }
                            if (expectedBytes != null && received != expectedBytes) {
                                failure(DiagnosticDownloadFailure.BODY_INCOMPLETE)
                            }
                        } catch (error: IOException) {
                            // Only observable framing failures get this classification, never message matching.
                            if (meta.downloadFailure == null &&
                                (error is EOFException || (expectedBytes != null && received < expectedBytes))) {
                                failure(DiagnosticDownloadFailure.BODY_INCOMPLETE)
                            }
                            throw error
                        } finally {
                            channel.cancel(null)
                        }
                        val text = body.toString((response.charset() ?: Charsets.UTF_8).name())
                        meta.responseChars = text.length.toLong()
                        WahlapPageResponse(response.status.value, response.headers[HttpHeaders.ContentType],
                            text, response.call.request.url.toString())
                    } catch (error: Throwable) {
                        // CIO can still be waiting on the network before the first body read.
                        // Cancel the call itself before execute's cleanup joins its children.
                        startWatchdog.cancel()
                        response.cancel("Score download stopped", error)
                        throw error
                    }
                }
            } finally {
                startWatchdog.cancel()
            }
        }
    } catch (cancelled: CancellationException) {
        meta.downloadFailure = DiagnosticDownloadFailure.CANCELLED
        throw cancelled
    } catch (error: ConnectTimeoutException) {
        meta.downloadFailure = DiagnosticDownloadFailure.CONNECT_TIMEOUT
        throw error
    }
}
