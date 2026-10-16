package dev.fluentmai.android.core.importer

import java.io.IOException
import dev.fluentmai.android.core.model.DiagnosticDownloadFailure
import kotlin.math.pow
import kotlin.random.Random

/**
 * Categories of outbound Wahlap requests. The category selects the timeout profile and the retry
 * policy, so single-use authorization operations can never inherit the retry behavior of ordinary
 * idempotent page GETs.
 */
enum class WahlapRequestCategory(val safeLabel: String) {
    /** GET of the OAuth authorize entry that produces the WeChat redirect URL. Never retried. */
    AUTH_AUTHORIZE("auth-authorize"),

    /** GET of the captured OAuth callback URL. Carries a single-use code; never retried. */
    AUTH_CALLBACK("auth-callback"),

    /** Home page probe used to verify a login produced an authenticated session. */
    LOGIN_HOME("login-home"),

    /** Full score listing page for one difficulty; the largest and slowest Wahlap pages. */
    SCORE_PAGE("score-page"),

    /** Rating and other supplemental pages fetched after the difficulty pages. */
    SUPPLEMENTAL_PAGE("supplemental-page"),
}

/**
 * A null total budget is allowed only with independent response-start and read-idle guards.
 * Score pages have no wall-clock download limit: only new body bytes count as progress.
 */
data class WahlapTimeoutProfile(
    val requestTimeoutMs: Long?,
    val connectTimeoutMs: Long,
    val responseStartTimeoutMs: Long? = null,
    val readIdleTimeoutMs: Long? = null,
)

/**
 * Bounded retry policy for one request category. [maxAttempts] is the total number of attempts
 * including the first; backoff grows exponentially with per-attempt jitter.
 */
data class WahlapRetryPolicy(
    val maxAttempts: Int,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
    val backoffMultiplier: Double,
    val jitterRatio: Double,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must be >= 0" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must be >= initialBackoffMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0.0..1.0" }
    }

    companion object {
        /** Single attempt, no delay: used for OAuth operations whose inputs are single-use. */
        val NO_RETRY = WahlapRetryPolicy(
            maxAttempts = 1,
            initialBackoffMs = 0,
            maxBackoffMs = 0,
            backoffMultiplier = 1.0,
            jitterRatio = 0.0,
        )
    }
}

/**
 * Default timeout and retry profiles per request category.
 *
 * Score pages use generous inactivity windows instead of a total request budget. A slow
 * download may take minutes while bytes continue arriving. Auth operations remain single-attempt:
 * the OAuth callback carries a
 * single-use code, and re-sending it cannot recover from a failure and can actively harm.
 */
object WahlapRequestCatalog {
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val SCORE_IDLE_TIMEOUT_MS = 90_000L

    /** HTTP statuses worth one more idempotent attempt: classic transient gateway/server errors. */
    val retryableStatuses: Set<Int> = setOf(408, 429, 500, 502, 503, 504)

    fun timeoutProfile(category: WahlapRequestCategory): WahlapTimeoutProfile {
        val requestTimeoutMs = when (category) {
            WahlapRequestCategory.SCORE_PAGE -> null
            WahlapRequestCategory.SUPPLEMENTAL_PAGE -> 45_000L
            WahlapRequestCategory.AUTH_AUTHORIZE,
            WahlapRequestCategory.AUTH_CALLBACK,
            WahlapRequestCategory.LOGIN_HOME, -> 30_000L
        }
        return WahlapTimeoutProfile(
            requestTimeoutMs = requestTimeoutMs,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            responseStartTimeoutMs = SCORE_IDLE_TIMEOUT_MS.takeIf { category == WahlapRequestCategory.SCORE_PAGE },
            readIdleTimeoutMs = SCORE_IDLE_TIMEOUT_MS.takeIf { category == WahlapRequestCategory.SCORE_PAGE },
        )
    }

    fun retryPolicy(category: WahlapRequestCategory): WahlapRetryPolicy = when (category) {
        WahlapRequestCategory.AUTH_AUTHORIZE,
        WahlapRequestCategory.AUTH_CALLBACK, -> WahlapRetryPolicy.NO_RETRY

        WahlapRequestCategory.SUPPLEMENTAL_PAGE -> WahlapRetryPolicy(
            maxAttempts = 2,
            initialBackoffMs = 1_000,
            maxBackoffMs = 1_000,
            backoffMultiplier = 1.0,
            jitterRatio = DEFAULT_JITTER_RATIO,
        )

        WahlapRequestCategory.LOGIN_HOME,
        WahlapRequestCategory.SCORE_PAGE, -> WahlapRetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 1_000,
            maxBackoffMs = 2_000,
            backoffMultiplier = 2.0,
            jitterRatio = DEFAULT_JITTER_RATIO,
        )
    }

    private const val DEFAULT_JITTER_RATIO = 0.2
}

/** HTTP-level failure with a status the retry engine can classify without parsing messages. */
class WahlapHttpStatusException(
    val statusCode: Int,
) : IOException("Wahlap request failed with HTTP status=$statusCode")

/**
 * Failure that must not be retried even though it is an IOException: the server told us the
 * session is not usable (login/auth pages), so repeating the request cannot change the outcome.
 */
open class WahlapNonRetryableException(
    message: String,
) : IOException(message)

class WahlapAuthFailurePageException(
    message: String,
) : WahlapNonRetryableException(message)

/** Outcome of one attempt, for diagnostics. */
enum class WahlapAttemptOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    NON_RETRYABLE_FAILURE,
    CANCELLED,
}

/**
 * Mutable side-channel a request block fills in so attempt logs can carry structured response
 * facts. Only primitive fields are allowed; free text is deliberately excluded so a diagnostics
 * line can never leak credentials, headers, or URLs.
 */
class WahlapAttemptMeta {
    var httpStatus: Int? = null
    /** Length of the decoded response String; not wire bytes. */
    var responseChars: Long? = null
    var responseBytes: Long? = null
    var progressChunks: Long? = null
    var downloadFailure: DiagnosticDownloadFailure? = null
}

/**
 * One completed attempt, described only through privacy-safe primitives. [toSafeLogLine] is the
 * exact string that reaches logs/UI; it is assembled from the fields below and never from
 * exception messages or request URLs.
 */
data class WahlapAttemptLog(
    val category: String,
    val attempt: Int,
    val maxAttempts: Int,
    val elapsedMs: Long,
    val outcome: WahlapAttemptOutcome,
    val willRetry: Boolean,
    val httpStatus: Int? = null,
    val responseChars: Long? = null,
    val errorType: String? = null,
    val responseBytes: Long? = null,
    val progressChunks: Long? = null,
    val downloadFailure: DiagnosticDownloadFailure? = null,
) {
    fun toSafeLogLine(): String = buildString {
        append("category=").append(category)
        append(" attempt=").append(attempt).append('/').append(maxAttempts)
        append(" elapsedMs=").append(elapsedMs)
        append(" outcome=").append(outcome.name.lowercase())
        append(" willRetry=").append(willRetry)
        httpStatus?.let { append(" status=").append(it) }
        responseChars?.let { append(" chars=").append(it) }
        responseBytes?.let { append(" bytes=").append(it) }
        progressChunks?.let { append(" chunks=").append(it) }
        downloadFailure?.let { append(" downloadFailure=").append(it.name.lowercase()) }
        errorType?.let { append(" error=").append(it) }
    }

    companion object {
        /**
         * Exception classes are summarized by their simple name only; message content is dropped
         * because Ktor/IO messages can embed request URLs.
         */
        fun errorTypeOf(error: Throwable): String = error::class.java.simpleName.ifBlank {
            "Throwable"
        }
    }
}

/**
 * Classifier deciding whether a failed attempt may be retried. IOException covers socket and
 * connect failures plus Ktor's HttpRequestTimeoutException (an IOException subclass in Ktor 3).
 * Status-based and auth-page markers are recognized even when wrapped by callers adding context,
 * and take precedence over the generic IOException rule.
 */
object WahlapRetryClassifier {
    fun isRetryable(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is WahlapNonRetryableException -> return false
                is WahlapHttpStatusException ->
                    return current.statusCode in WahlapRequestCatalog.retryableStatuses
                else -> Unit
            }
            current = current.cause
            depth += 1
        }
        return error is IOException
    }

    private const val MAX_CAUSE_DEPTH = 5
}

/** Picks the backoff delay before the next attempt: exponential growth with symmetric jitter. */
object WahlapBackoff {
    fun delayMs(
        attempt: Int,
        policy: WahlapRetryPolicy,
        random: Random = Random.Default,
    ): Long {
        if (attempt <= 0 || policy.initialBackoffMs <= 0L) return 0L
        val growth = policy.backoffMultiplier.pow((attempt - 1).toDouble())
        val base = (policy.initialBackoffMs * growth).toLong().coerceAtMost(policy.maxBackoffMs)
        if (policy.jitterRatio <= 0.0) return base
        val jitter = 1.0 + (random.nextDouble() * 2.0 - 1.0) * policy.jitterRatio
        return (base * jitter).toLong().coerceIn(0L, policy.maxBackoffMs)
    }
}
