package codes.momo.agent

import ai.router.sdk.models.AiRouterException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block], retrying it once per [backoffs] entry when it fails
 * transiently, sleeping that entry's duration before the retry. Any
 * non-transient failure, and the last transient one once the schedule is
 * spent, propagates unchanged; a cancellation always propagates untouched.
 *
 * [onRetry] runs before each backoff sleep; its attempt number is 1-based.
 */
internal suspend fun <T> retryTransientFailures(
    backoffs: List<Duration> = RETRY_BACKOFFS,
    onRetry: (cause: Exception, attempt: Int, backoff: Duration) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    backoffs.forEachIndexed { index, backoff ->
        try {
            return block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            if (!failure.isTransient) {
                throw failure
            }
            onRetry(failure, index + 1, backoff)
        }
        delay(backoff)
    }
    return block()
}

/**
 * Transient per the retry policy: rate limiting or a server-side failure
 * the router reported, or a connection-level failure that never got a
 * response at all — the router down ([IOException], which Ktor's timeout
 * exceptions extend) or its host unresolvable ([UnresolvedAddressException],
 * which extends [IllegalArgumentException] rather than [IOException]).
 */
internal val Exception.isTransient: Boolean
    get() = when (this) {
        is AiRouterException -> statusCode == HTTP_TOO_MANY_REQUESTS || statusCode in HTTP_SERVER_ERRORS
        is IOException, is UnresolvedAddressException -> true
        else -> false
    }

/** Sleeps between a failed LLM call and its retries: quick first, patient later. */
internal val RETRY_BACKOFFS: List<Duration> = listOf(5.seconds, 1.minutes, 5.minutes)

private const val HTTP_TOO_MANY_REQUESTS: Int = 429
private const val HTTP_FIRST_SERVER_ERROR: Int = 500
private const val HTTP_LAST_SERVER_ERROR: Int = 599
private val HTTP_SERVER_ERRORS: IntRange = HTTP_FIRST_SERVER_ERROR..HTTP_LAST_SERVER_ERROR
