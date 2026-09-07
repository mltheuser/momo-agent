package codes.momo.agent.internal

import ai.router.sdk.models.AiRouterException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

private val Exception.isTransient: Boolean
    get() = when (this) {
        is AiRouterException -> statusCode == HTTP_TOO_MANY_REQUESTS || statusCode in HTTP_SERVER_ERRORS
        is IOException, is UnresolvedAddressException -> true
        else -> false
    }

internal val RETRY_BACKOFFS: List<Duration> = listOf(5.seconds, 1.minutes, 5.minutes)

private const val HTTP_TOO_MANY_REQUESTS: Int = 429
private const val HTTP_FIRST_SERVER_ERROR: Int = 500
private const val HTTP_LAST_SERVER_ERROR: Int = 599
private val HTTP_SERVER_ERRORS: IntRange = HTTP_FIRST_SERVER_ERROR..HTTP_LAST_SERVER_ERROR
