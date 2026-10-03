package dev.fluentmai.android.core.importer

import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Executes a Wahlap request block with the category's timeout profile and a bounded
 * retry/backoff loop for transient failures.
 *
 * Cancellation safety: real coroutine cancellation (user leaving, scope teardown) always
 * rethrows. Ktor's HttpRequestTimeoutException is an IOException subclass, not a
 * CancellationException, so catching it does not swallow cancellation; after every caught error
 * the fetcher re-checks that its own scope is still active before scheduling a retry.
 *
 * The block receives the timeout profile to apply to the raw request and an attempt-meta
 * side-channel for structured response facts used in diagnostics.
 */
class WahlapResilientFetcher(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val delayFor: suspend (Long) -> Unit = { delayMs -> kotlinx.coroutines.delay(delayMs) },
    private val random: Random = Random.Default,
) {
    suspend fun <T> fetch(
        category: WahlapRequestCategory,
        retryPolicy: WahlapRetryPolicy = WahlapRequestCatalog.retryPolicy(category),
        onAttempt: (WahlapAttemptLog) -> Unit = {},
        block: suspend (profile: WahlapTimeoutProfile, meta: WahlapAttemptMeta) -> T,
    ): T {
        val timeoutProfile = WahlapRequestCatalog.timeoutProfile(category)
        var attempt = 0
        while (true) {
            attempt += 1
            val meta = WahlapAttemptMeta()
            val startedAtMs = nowMs()
            try {
                val value = block(timeoutProfile, meta)
                onAttempt(
                    WahlapAttemptLog(
                        category = category.safeLabel,
                        attempt = attempt,
                        maxAttempts = retryPolicy.maxAttempts,
                        elapsedMs = nowMs() - startedAtMs,
                        outcome = WahlapAttemptOutcome.SUCCESS,
                        willRetry = false,
                        httpStatus = meta.httpStatus,
                        responseChars = meta.responseChars,
                    ),
                )
                return value
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()

                val retryable = WahlapRetryClassifier.isRetryable(error)
                val willRetry = retryable && attempt < retryPolicy.maxAttempts
                onAttempt(
                    WahlapAttemptLog(
                        category = category.safeLabel,
                        attempt = attempt,
                        maxAttempts = retryPolicy.maxAttempts,
                        elapsedMs = nowMs() - startedAtMs,
                        outcome = if (retryable) {
                            WahlapAttemptOutcome.RETRYABLE_FAILURE
                        } else {
                            WahlapAttemptOutcome.NON_RETRYABLE_FAILURE
                        },
                        willRetry = willRetry,
                        httpStatus = meta.httpStatus,
                        responseChars = meta.responseChars,
                        errorType = WahlapAttemptLog.errorTypeOf(error),
                    ),
                )
                if (!willRetry) throw error
                delayFor(WahlapBackoff.delayMs(attempt, retryPolicy, random))
            }
        }
    }
}
