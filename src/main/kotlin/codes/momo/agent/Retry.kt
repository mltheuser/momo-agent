package codes.momo.agent

import ai.router.sdk.models.AiRouterException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block], retrying up to [MAX_LLM_RETRIES] times when it fails
 * transiently, sleeping exponentially longer before each retry (from
 * [INITIAL_RETRY_BACKOFF], doubling). Any non-transient failure, and the
 * last transient one once the retries are used up, propagates unchanged.
 */
internal suspend fun <T> retryTransientFailures(block: suspend () -> T): T {
    var backoff = INITIAL_RETRY_BACKOFF
    repeat(MAX_LLM_RETRIES) {
        try {
            return block()
        } catch (failure: AiRouterException) {
            if (!failure.isTransient) {
                throw failure
            }
        }
        delay(backoff)
        backoff *= 2
    }
    return block()
}

/** Transient per the retry policy: rate limiting or a server-side failure. */
internal val AiRouterException.isTransient: Boolean
    get() = statusCode == HTTP_TOO_MANY_REQUESTS || statusCode in HTTP_SERVER_ERRORS

/** Retry cap for transient LLM failures. */
internal const val MAX_LLM_RETRIES: Int = 3

/** Sleep before the first retry; doubled for each further one. */
internal val INITIAL_RETRY_BACKOFF: Duration = 1.seconds

private const val HTTP_TOO_MANY_REQUESTS: Int = 429
private const val HTTP_FIRST_SERVER_ERROR: Int = 500
private const val HTTP_LAST_SERVER_ERROR: Int = 599
private val HTTP_SERVER_ERRORS: IntRange = HTTP_FIRST_SERVER_ERROR..HTTP_LAST_SERVER_ERROR
