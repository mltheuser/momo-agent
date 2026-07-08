package codes.momo.agent

import ai.router.sdk.AiRouterClient
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.runProcess
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Planted greeting no prompt contains — only asking the user can reveal it. */
private const val GREETING_TOKEN: String = "xyzzy-4711"

private const val SCRIPT_NAME: String = "greeting.sh"

private const val TASK_PROMPT: String =
    "Create a shell script named $SCRIPT_NAME in the workspace root that prints one exact " +
        "greeting message and exits successfully. The user has already decided on the exact " +
        "message, but it is written down nowhere: ask the user for it with the ask_user tool, " +
        "as one single question — do not ask the user anything else before or after. Then " +
        "write the script, run it to verify it prints the message, and finish with a short summary."

private const val CANNED_ANSWER: String = "The script must print exactly this message: $GREETING_TOKEN"

/**
 * Drives the toy task end to end over [environment] and asserts everything
 * except the workspace outcome, which the caller checks host-side via
 * [assertToyTaskWorkspace].
 */
internal fun runToyTaskScenario(environment: ExecutionEnvironment) {
    val harness = Harness.load(Path.of(examplesDir, "coder")).copy(model = liveChatModel)
    val listener = CollectingEventListener()
    runBlocking {
        AiRouterClient(liveBaseUrl).use { client ->
            val agent = Agent(harness, client, environment, "E2E acceptance session", listener)
            assertSegments(driveScriptedUser(agent))
        }
    }
    assertEventLog(listener.events)
}

/** Isolation check for the container variant: the script has not reached the host yet. */
internal fun assertToyTaskAbsent(workspace: Path) {
    assertFalse(
        workspace.resolve(SCRIPT_NAME).exists(),
        "the container workspace must reach the host only on close()",
    )
}

/** Host-side mechanical check: the script exists, runs clean, and prints the planted greeting. */
internal fun assertToyTaskWorkspace(workspace: Path) {
    val script = workspace.resolve(SCRIPT_NAME)
    assertTrue(script.isRegularFile(), "expected the agent to create $script")
    val run = runBlocking {
        runProcess(listOf("bash", SCRIPT_NAME), workingDirectory = workspace, timeout = 30.seconds)
    }
    val completed = assertIs<ExecResult.Completed>(run, "the greeting script timed out")
    assertEquals(0, completed.exitCode, "the greeting script failed: ${completed.stderr}")
    assertContains(completed.stdout, GREETING_TOKEN)
}

/** Sends the task, then answers the single question the task allows; a second ask fails fast. */
private suspend fun driveScriptedUser(agent: Agent): List<PromptResult> {
    val segments = mutableListOf(agent.send(AgentInput.UserMessage(TASK_PROMPT)))
    while (segments.last().status == PromptResult.Status.AWAITING_USER) {
        assertEquals(
            1,
            segments.size,
            "the agent asked a second question instead of finishing: ${segments.last().pendingQuestion}",
        )
        segments += agent.send(AgentInput.Answer(CANNED_ANSWER))
    }
    return segments
}

private fun assertSegments(segments: List<PromptResult>) {
    val final = segments.last()
    assertEquals(PromptResult.Status.COMPLETED, final.status, "error: ${final.error}")
    val pauses = segments.dropLast(1)
    assertTrue(pauses.isNotEmpty(), "the agent never asked the user, yet the greeting is not in the prompt")
    pauses.forEach { pause ->
        assertNotNull(pause.pendingQuestion, "an AWAITING_USER segment must carry its question")
    }
    assertToolCallsAnswered(final.transcript)
    val last = final.transcript.last()
    assertEquals("assistant", last.role)
    assertTrue(last.toolCalls.isNullOrEmpty(), "the final message must not carry tool calls")
}

private fun assertEventLog(events: List<AgentEvent>) {
    assertIs<AgentEvent.SessionStarted>(events.first())
    assertEquals(1, events.count { it is AgentEvent.RunStarted }, "one prompt means one RunStarted")
    val llmCalls = events.count { it is AgentEvent.LlmCallStarted }
    assertTrue(llmCalls >= 1, "expected at least one LLM call")
    assertEquals(llmCalls, events.count { it is AgentEvent.LlmCallFinished }, "every LLM call must finish")
    val toolCalls = events.count { it is AgentEvent.ToolCallStarted }
    assertTrue(toolCalls >= 1, "expected tool activity — the script cannot appear without it")
    assertEquals(toolCalls, events.count { it is AgentEvent.ToolCallFinished }, "every tool call must finish")

    val asked = events.filterIsInstance<AgentEvent.QuestionAsked>().single()
    val answered = events.filterIsInstance<AgentEvent.QuestionAnswered>().single()
    assertEquals(asked.callId, answered.callId, "the answer must resolve the asked call")
    assertEquals(CANNED_ANSWER, answered.answer)

    // Exactly one terminal event — the pause emitted none — and it closes the log.
    val finished = events.filterIsInstance<AgentEvent.RunFinished>().single()
    assertEquals(PromptResult.Status.COMPLETED, finished.status)
    assertEquals(finished, events.last())

    assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId }, "sequence IDs must be gapless")
    assertTrue(events.all { it.timestampMillis > 0 }, "every event must carry a timestamp")
}
