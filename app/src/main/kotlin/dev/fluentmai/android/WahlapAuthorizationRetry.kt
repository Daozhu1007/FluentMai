package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapNonRetryableException
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Only emitted after a completed callback and a Home response classified unauthenticated. */
internal class WahlapAuthorizationRetryRequiredException :
    WahlapNonRetryableException("本次授权已失效，请重新授权")

internal enum class ImportFailureCategory {
    AUTHORIZATION_RETRY_REQUIRED, CAPTURE_VPN, AUTHORIZE_GENERATION,
    NETWORK_TRANSPORT, CANCELLED, SCORE_PAGE, IMPORT,
}

/** Wrappers may add sanitized diagnostics; classification never depends on their text. */
internal fun importFailureCategory(error: Throwable, authenticated: Boolean): ImportFailureCategory {
    val causes = generateSequence(error) { it.cause }.take(20).toList()
    return when {
        causes.any { it is CancellationException } -> ImportFailureCategory.CANCELLED
        !authenticated && causes.any { it is WahlapAuthorizationRetryRequiredException } ->
            ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED
        authenticated -> ImportFailureCategory.SCORE_PAGE
        causes.any { it is IOException } -> ImportFailureCategory.NETWORK_TRANSPORT
        else -> ImportFailureCategory.IMPORT
    }
}
