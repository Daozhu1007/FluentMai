package dev.fluentmai.android.core.importer

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WahlapResilientFetcherTest {
    private class TransientNetworkException(message: String) : IOException(message)

    private class FatalException(message: String) : RuntimeException(message)

    private class HttpRequestTimeoutLikeException(message: String) : IOException(message)

    @Test
    fun scoreGetSucceedsOnFirstAttemptWithoutDelays() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("no retry delay expected") })
        val attempts = mutableListOf<Int>()
        val logs = mutableListOf<WahlapAttemptLog>()

        val value = fetcher.fetch(
            category = WahlapRequestCategory.SCORE_PAGE,
            onAttempt = logs::add,
        ) { profile, _ ->
            attempts += 1
            assertEquals(60_000L, profile.requestTimeoutMs)
            assertEquals(15_000L, profile.connectTimeoutMs)
            "page"
        }

        assertEquals("page", value)
        assertEquals(listOf(1), attempts)
        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals(WahlapAttemptOutcome.SUCCESS, log.outcome)
        assertFalse(log.willRetry)
        assertEquals("score-page", log.category)
    }

    @Test
    fun scoreGetTimesOutThenSucceedsOnRetry() = runTest {
        val delays = mutableListOf<Long>()
        val fetcher = WahlapResilientFetcher(delayFor = { delays += it })
        var attempt = 0
        val logs = mutableListOf<WahlapAttemptLog>()

        val value = fetcher.fetch(
            category = WahlapRequestCategory.SCORE_PAGE,
            onAttempt = logs::add,
        ) { _, _ ->
            attempt += 1
            if (attempt == 1) throw HttpRequestTimeoutLikeException("request_timeout=60000 ms")
            "page"
        }

        assertEquals("page", value)
        assertEquals(2, attempt)
        assertEquals(1, delays.size)
        assertTrue("first backoff should be about 1s", delays.single() in 800..1200)
        assertEquals(WahlapAttemptOutcome.RETRYABLE_FAILURE, logs[0].outcome)
        assertTrue(logs[0].willRetry)
        assertEquals(WahlapAttemptOutcome.SUCCESS, logs[1].outcome)
        assertEquals("HttpRequestTimeoutLikeException", logs[0].errorType)
    }

    @Test
    fun repeatedTransientFailuresStopAfterBoundedRetries() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { delay(it) })
        var attempts = 0
        val logs = mutableListOf<WahlapAttemptLog>()

        val error = runCatching {
            fetcher.fetch(
                category = WahlapRequestCategory.SCORE_PAGE,
                onAttempt = logs::add,
            ) { _, _ ->
                attempts += 1
                throw SocketTimeoutException("read timed out")
            }
        }.exceptionOrNull()

        assertTrue(error is SocketTimeoutException)
        assertEquals(3, attempts)
        assertEquals(3, logs.size)
        assertTrue(logs.dropLast(1).all { it.willRetry })
        assertFalse(logs.last().willRetry)
    }

    @Test
    fun nonRetryableHttpStatusIsRequestedExactlyOnce() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("no retry delay expected") })
        var attempts = 0

        val error = runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw WahlapHttpStatusException(statusCode = 404)
            }
        }.exceptionOrNull()

        assertTrue(error is WahlapHttpStatusException)
        assertEquals(1, attempts)
    }

    @Test
    fun transientHttpStatusesAreRetriedUpToTheBound() = runTest {
        var serverErrorAttempts = 0
        var tooManyRequestsAttempts = 0
        val fetcher = WahlapResilientFetcher(delayFor = { delay(it) })

        runCatching {
            fetcher.fetch(WahlapRequestCategory.LOGIN_HOME) { _, _ ->
                serverErrorAttempts += 1
                throw WahlapHttpStatusException(statusCode = 503)
            }
        }
        runCatching {
            fetcher.fetch(WahlapRequestCategory.LOGIN_HOME) { _, _ ->
                tooManyRequestsAttempts += 1
                throw WahlapHttpStatusException(statusCode = 429)
            }
        }

        assertEquals(3, serverErrorAttempts)
        assertEquals(3, tooManyRequestsAttempts)
    }

    @Test
    fun oauthCallbackCategoryIsNeverRetriedEvenOnTransientErrors() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("auth callback must not back off") })
        var attempts = 0
        val logs = mutableListOf<WahlapAttemptLog>()

        val error = runCatching {
            fetcher.fetch(
                category = WahlapRequestCategory.AUTH_CALLBACK,
                onAttempt = logs::add,
            ) { _, _ ->
                attempts += 1
                throw TransientNetworkException("connection reset")
            }
        }.exceptionOrNull()

        assertTrue(error is TransientNetworkException)
        assertEquals(1, attempts)
        assertEquals(WahlapAttemptOutcome.RETRYABLE_FAILURE, logs.single().outcome)
        assertFalse(logs.single().willRetry)
        // The default catalog policy itself must be single-attempt, not just this call.
        assertEquals(1, WahlapRequestCatalog.retryPolicy(WahlapRequestCategory.AUTH_CALLBACK).maxAttempts)
        assertEquals(1, WahlapRequestCatalog.retryPolicy(WahlapRequestCategory.AUTH_AUTHORIZE).maxAttempts)
    }

    @Test
    fun authFailurePageMarkerIsNotRetriedEvenThoughItIsAnIoException() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("auth failure must not back off") })
        var attempts = 0

        runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw IOException(
                    "score fetch failed: session expired",
                    WahlapAuthFailurePageException("auth failure page"),
                )
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun wrappedTransientHttpStatusMarkerIsRetriedThroughTheCauseChain() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { delay(it) })
        var attempts = 0

        runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw IOException("score fetch failed: status=503", WahlapHttpStatusException(503))
            }
        }

        assertEquals(3, attempts)
    }

    @Test
    fun nonIoUnexpectedFailuresAreNotRetried() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("unexpected failure must not back off") })
        var attempts = 0

        runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw FatalException("parser blew up")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun realCancellationIsNeverSwallowedByTheRetryLoop() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = { fail("cancelled attempt must not back off") })
        var attempts = 0

        val error = runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw CancellationException("scope torn down")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationRaisedDuringBackoffStopsTheLoopWithoutFurtherAttempts() = runTest {
        var attempts = 0
        val fetcher = WahlapResilientFetcher(
            delayFor = { _ -> throw CancellationException("caller cancelled during backoff") },
        )

        val error = runCatching {
            fetcher.fetch(WahlapRequestCategory.SCORE_PAGE) { _, _ ->
                attempts += 1
                throw SocketTimeoutException("read timed out")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun backoffGrowsExponentiallyAndNeverExceedsTheCap() {
        val policy = WahlapRetryPolicy(
            maxAttempts = 6,
            initialBackoffMs = 1_000,
            maxBackoffMs = 2_000,
            backoffMultiplier = 2.0,
            jitterRatio = 0.0,
        )
        assertEquals(1_000L, WahlapBackoff.delayMs(1, policy))
        assertEquals(2_000L, WahlapBackoff.delayMs(2, policy))
        assertEquals(2_000L, WahlapBackoff.delayMs(3, policy))
        assertEquals(2_000L, WahlapBackoff.delayMs(10, policy))
    }

    @Test
    fun attemptLogLineCarriesMetadataWithoutFreeText() = runTest {
        val fetcher = WahlapResilientFetcher(delayFor = {})
        val logs = mutableListOf<WahlapAttemptLog>()

        fetcher.fetch(
            category = WahlapRequestCategory.SCORE_PAGE,
            onAttempt = logs::add,
        ) { _, meta ->
            meta.httpStatus = 200
            meta.responseChars = 482_113L
            "page"
        }

        val line = logs.single().toSafeLogLine()
        assertTrue(line.contains("category=score-page"))
        assertTrue(line.contains("attempt=1/3"))
        assertTrue(line.contains("elapsedMs="))
        assertTrue(line.contains("outcome=success"))
        assertTrue(line.contains("status=200"))
        assertTrue(line.contains("chars=482113"))
        assertFalse(line.contains("cookie", ignoreCase = true))
        assertFalse(line.contains("token", ignoreCase = true))
        assertFalse(line.contains("https://"))
    }
}
