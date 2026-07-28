package codes.momo.agent

import ai.router.sdk.models.AiRouterException
import ai.router.sdk.models.ChatRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The fake router's own contract: a request its script cannot answer fails
 * where it arrives, naming both sides, and does so at once. The bound is what
 * makes "cannot hang" an observation rather than a claim. Its one deliberate
 * escape hatch — a reply that throws — is pinned here too, since a fake that
 * lost it would leave the cases resting on it passing for another reason.
 */
class FakeLlmTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("A request no rule answers fails at once, naming what arrived and what the script expected")
    fun unanswerableRequestFailsWithoutWaiting() {
        val fake = FakeLlm(onToolResults(assistantResponse(finishReason = "stop", text = "never served")))

        val (failure, elapsed) = fake.client().use { client ->
            timed { assertFailsWith<AiRouterException> { runBlocking { client.chat(openingRequest()) } } }
        }

        assertUnderTheBound(elapsed)
        val message = failure.message.orEmpty()
        assertContains(message, "whose newest message is a user saying 'hello'", ignoreCase = false)
        assertContains(message, "a turn after tool results")
    }

    @Test
    @DisplayName("A rule that has served its allowance stops answering, and the next request fails at once")
    fun exhaustedRuleFailsWithoutWaiting() {
        val fake = FakeLlm(
            onAnyTurn(
                reply = FakeLlmReply.Completion(assistantResponse(finishReason = "stop", text = "once")),
                expectation = "exactly one turn",
                uses = 1,
            ),
        )

        val (failure, elapsed) = fake.client().use { client ->
            runBlocking { client.chat(openingRequest()) }
            timed { assertFailsWith<AiRouterException> { runBlocking { client.chat(openingRequest()) } } }
        }

        assertUnderTheBound(elapsed)
        // The count the exhaustion message reports is the one the rule served.
        assertContains(failure.message.orEmpty(), "exactly one turn (at most 1 request(s), 1 so far)")
    }

    @Test
    @DisplayName("An agent whose run outruns its script ends the run as an error at once, naming the missing reply")
    fun anAgentOutrunningItsScriptFailsWithoutWaiting() {
        // One turn is scripted; the tool result it asks for leaves the loop
        // needing a second the script does not have. Only the send is timed:
        // building the agent probes the environment's privileges, work the
        // bound is not about and production allows seconds for.
        val (run, elapsed) = workspace.withFakeAgent(
            onOpeningTurn(toolCallResponse(bashCall("call-1", "echo hi"))),
        ) { agent ->
            timed { agent.send("go", TEST_RUN_SETTINGS) }
        }

        assertUnderTheBound(elapsed)
        assertEquals(RunResult.Status.ERROR, run.status)
        assertContains(assertNotNull(run.error).message.orEmpty(), "The fake router has no reply for")
    }

    @Test
    @DisplayName("A reply that throws surfaces at the call as the throwable it planted")
    fun aThrowingReplySurfacesAtTheCall() {
        val fake = FakeLlm(
            onAnyTurn(
                reply = FakeLlmReply.Thrown { PlantedError("planted where the reply would be") },
                expectation = "any turn, raising an Error instead of answering",
            ),
        )

        fake.client().use { client ->
            assertFailsWith<PlantedError> { runBlocking { client.chat(openingRequest()) } }
        }
    }

    private fun openingRequest(): ChatRequest =
        ChatRequest(model = TEST_RUN_SETTINGS.model, messages = listOf(userMessage("hello")))

    // Inline, so the timed region can be a suspending call.
    private inline fun <T> timed(block: () -> T): Pair<T, Duration> {
        val started = TimeSource.Monotonic.markNow()
        return block() to started.elapsedNow()
    }

    private fun assertUnderTheBound(elapsed: Duration) {
        assertTrue(elapsed < FAIL_FAST_BOUND, "expected a failure inside $FAIL_FAST_BOUND, took $elapsed")
    }
}

/**
 * Generous by two orders of magnitude — the failure needs no IO at all — so
 * only an actual wait can breach it.
 */
private val FAIL_FAST_BOUND: Duration = 2.seconds
