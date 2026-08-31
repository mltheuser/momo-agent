package codes.momo.agent

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.runProcess
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Planted greeting no prompt contains — only asking the user can reveal it. */
private const val GREETING_TOKEN: String = "xyzzy-4711"

private const val SCRIPT_NAME: String = "greeting.sh"

private const val TASK_PROMPT: String =
    "Create a shell script named $SCRIPT_NAME in the workspace root that prints one exact " +
        "greeting message and exits successfully. The user has already decided on the exact " +
        "message, but it is written down nowhere: ask the user for it, as one single question — " +
        "do not ask the user anything else before or after. Once you have the message, write " +
        "the script, run it to verify it prints the message, and finish with a short summary."

private const val CANNED_ANSWER: String = "The script must print exactly this message: $GREETING_TOKEN"

/**
 * Drives the toy task end to end over [environment] — the first run ends
 * with the agent's question as its final message, the next prompt answers
 * it — and asserts everything except the workspace outcome, which the
 * caller checks host-side via [assertToyTaskWorkspace].
 */
public fun runToyTaskScenario(environment: ExecutionEnvironment) {
    val harness = Harness.load(Path.of(examplesDir, "coder"))
    val listener = CollectingEventListener()
    runBlocking {
        liveAiRouterClient().use { client ->
            val agent = liveAgent(harness, client, environment, "E2E acceptance session", listener)

            val asked = agent.send(TASK_PROMPT, liveRunSettings)
            assertEquals(RunResult.Status.COMPLETED, asked.status, "error: ${asked.error}")
            assertOnlyAsked(environment)

            val finished = agent.send(CANNED_ANSWER, liveRunSettings)
            assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
            assertToolCallsAnswered(finished.transcript)
            val last = finished.transcript.last()
            assertEquals("assistant", last.role)
            assertTrue(last.toolCalls.isNullOrEmpty(), "the final message must not carry tool calls")
        }
    }
    assertEventLog(listener.events)
}

/**
 * The first run must have ended by asking rather than delivering: no script
 * yet — the non-prose proxy for the question being its final message — and
 * the planted token nowhere in the workspace, since only the user can reveal
 * it.
 */
private suspend fun assertOnlyAsked(environment: ExecutionEnvironment) {
    environment.assertNotFound(
        listOf("bash", "-c", "test -e '${environment.workspacePath}/$SCRIPT_NAME'"),
        found = "$SCRIPT_NAME already existed after the run that was to end with the agent's question",
    )
    environment.assertNotFound(
        listOf("grep", "-r", GREETING_TOKEN, environment.workspacePath),
        found = "the greeting appeared in the workspace before the user revealed it",
    )
}

/**
 * Runs [command] as the question "is it there?", where exit 1 is the no both
 * `grep` and `test` report and 0 their yes. Every other code is the check
 * itself having failed — an unreadable directory, a symlink loop, a workspace
 * an agent wrote as root — and must never be read as a yes.
 */
private suspend fun ExecutionEnvironment.assertNotFound(command: List<String>, found: String) {
    val result = exec(command, timeout = 30.seconds)
    val completed = assertIs<ExecResult.Completed>(result, "$command timed out")
    when (completed.exitCode) {
        1 -> Unit
        0 -> fail(found)
        else -> fail("$command could not answer, exit ${completed.exitCode}: ${completed.stderr}")
    }
}

/** Host-side mechanical check: the script exists, runs clean, and prints the planted greeting. */
public fun assertToyTaskWorkspace(workspace: Path) {
    val script = workspace.resolve(SCRIPT_NAME)
    assertTrue(script.isRegularFile(), "expected the agent to create $script")
    val run = runBlocking {
        runProcess(listOf("bash", SCRIPT_NAME), workingDirectory = workspace, timeout = 30.seconds)
    }
    val completed = assertIs<ExecResult.Completed>(run, "the greeting script timed out")
    assertEquals(0, completed.exitCode, "the greeting script failed: ${completed.stderr}")
    assertContains(completed.stdout, GREETING_TOKEN)
}

private fun assertEventLog(events: List<AgentEvent>) {
    assertIs<AgentEvent.SessionStarted>(events.first())
    val llmCalls = events.count { it is AgentEvent.LlmCallStarted }
    assertTrue(llmCalls >= 2, "expected at least two LLM calls across the two runs")
    assertEquals(llmCalls, events.count { it is AgentEvent.LlmCallFinished }, "every LLM call must finish")
    val toolCalls = events.count { it is AgentEvent.ToolCallStarted }
    assertTrue(toolCalls >= 1, "expected tool activity — the script cannot appear without it")
    assertEquals(toolCalls, events.count { it is AgentEvent.ToolCallFinished }, "every tool call must finish")

    // Two clean runs: task → question, answer → deliverable.
    assertTwoCleanRuns(events, secondUserMessage = CANNED_ANSWER)
    assertTrue(events.all { it.timestampMillis > 0 }, "every event must carry a timestamp")
}
