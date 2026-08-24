package codes.momo.agent

import ai.router.sdk.models.AiRouterException
import ai.router.sdk.models.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Opt-in for the scheduler's virtual clock, read to pin backoff timing.
@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    private fun apiFailure(statusCode: Int): AiRouterException =
        AiRouterException(statusCode, ApiError(type = "test", message = "status $statusCode"))

    private val scheduleMillis = RETRY_BACKOFFS.map { it.inWholeMilliseconds }

    @Test
    @DisplayName("A block succeeding immediately runs once, without any delay")
    fun immediateSuccessRunsOnce() = runTest {
        var attempts = 0

        val result = retryTransientFailures {
            attempts++
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(1, attempts)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    @DisplayName("Transient failures are retried on the backoff schedule until the block succeeds")
    fun transientFailuresAreRetriedUntilSuccess() = runTest {
        var attempts = 0

        val result = retryTransientFailures {
            attempts++
            if (attempts <= 2) throw apiFailure(429)
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attempts)
        assertEquals(scheduleMillis[0] + scheduleMillis[1], testScheduler.currentTime)
    }

    @Test
    @DisplayName("A persistent transient failure propagates once the backoff schedule is spent")
    fun persistentTransientFailureGivesUp() = runTest {
        var attempts = 0

        val failure = assertFailsWith<AiRouterException> {
            retryTransientFailures {
                attempts++
                throw apiFailure(503)
            }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(RETRY_BACKOFFS.size + 1, attempts)
        assertEquals(scheduleMillis.sum(), testScheduler.currentTime)
    }

    @Test
    @DisplayName("A non-retryable status propagates immediately, without delay")
    fun clientErrorIsNotRetried() = runTest {
        var attempts = 0

        val failure = assertFailsWith<AiRouterException> {
            retryTransientFailures {
                attempts++
                throw apiFailure(400)
            }
        }

        assertEquals(400, failure.statusCode)
        assertEquals(1, attempts)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    @DisplayName("A connection-level failure is retried like a transient status")
    fun connectionFailureIsRetried() = runTest {
        var attempts = 0

        val result = retryTransientFailures {
            attempts++
            if (attempts == 1) throw IOException("Connection refused")
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(2, attempts)
        assertEquals(scheduleMillis[0], testScheduler.currentTime)
    }

    @Test
    @DisplayName("A failure that is neither an ai-router error nor connection-level propagates immediately")
    fun unrelatedFailureIsNotRetried() = runTest {
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            retryTransientFailures {
                attempts++
                error("not an API failure")
            }
        }

        assertEquals(1, attempts)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    @DisplayName("Classification: 429, the 5xx range and connection-level failures are transient, the rest is not")
    fun retryClassification() {
        assertTrue(apiFailure(429).isTransient)
        assertTrue(apiFailure(500).isTransient)
        assertTrue(apiFailure(503).isTransient)
        assertTrue(apiFailure(599).isTransient)
        assertTrue(IOException("Connection reset").isTransient)
        assertTrue(UnresolvedAddressException().isTransient)

        assertFalse(apiFailure(400).isTransient)
        assertFalse(apiFailure(404).isTransient)
        assertFalse(apiFailure(428).isTransient)
        assertFalse(apiFailure(600).isTransient)
        assertFalse(IllegalStateException("not transient").isTransient)
    }
}
