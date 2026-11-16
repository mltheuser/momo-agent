package codes.momo.agent.server

import ai.router.sdk.chat.FinishReason
import ai.router.sdk.chat.ToolCall
import ai.router.sdk.chat.ToolCallFunction
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.harnessPath
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.rig.FaultyRouter
import codes.momo.agent.server.rig.FaultyRouter.Reply
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.retryRun
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.session.SessionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouterFailureLiveTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var router: FaultyRouter

    private lateinit var server: LiveServerProcess

    private lateinit var dataDir: Path

    @BeforeAll
    fun startServerBehindTheStandIn() {
        router = FaultyRouter.start()
        dataDir = Files.createTempDirectory("momo-faulty-router")
        server = LiveServerProcess.start(dataDir, aiRouterBaseUrl = router.baseUrl)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterAll
    fun stopEverything() {
        server.close()
        router.close()
        dataDir.deleteRecursively()
    }

    private fun withFaultyServer(block: suspend (HttpClient) -> Unit): Unit = runBlocking {
        liveHttpClient(server.baseUrl).use { http -> block(http) }
    }

    @Test
    @DisplayName("A 503 is retried once after the production backoff, and the run completes over the real router")
    fun transientFailureIsRetriedThenTheRunCompletes() = withFaultyServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "transient")).id
        router.script(Reply.Status(503))

        http.prompt(id, "Reply with exactly this token and nothing else: $TOKEN")

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(assertNotNull(finished.finalMessage), TOKEN, message = "the forwarded call reached the model")
        val retries = events.filterIsInstance<AgentEvent.LlmCallRetried>()
        assertEquals(1, retries.size, "the one planted 503 costs exactly one retry")
        assertEquals(1, retries.single().attempt)
        assertContains(retries.single().cause, "503", message = "the retry names what it retried")
        assertEquals(1, events.count { it is AgentEvent.LlmCallStarted }, "a retry is not a new call")
    }

    @Test
    @DisplayName(
        "A response the provider refused ends the run in error; " +
            "a retry remakes the call, leaving no trace"
    )
    fun refusedResponseFailsTheRunAndARetryLeavesNoTrace() = withFaultyServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "refused")).id
        router.script(Reply.ended(FinishReason.CONTENT_FILTER))

        http.prompt(id, "Reply with exactly this token and nothing else: $TOKEN")

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.ERROR, finished.status)
        assertContains(assertNotNull(finished.error).message, "finish_reason 'content_filter'")
        assertNull(finished.finalMessage, "a refused response must not read as the model's answer")
        assertTrue(events.none { it is AgentEvent.LlmCallRetried }, "a 200 is never retried")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status, "the session stays idle")

        http.retryRun(id)
        val retried = http.awaitRunEnd(id)
        val prompt = events.single { it is AgentEvent.RunStarted }
        val survivors = events.takeWhile { it.sequenceId <= prompt.sequenceId }
        assertEquals(survivors, retried.take(survivors.size), "the log up to the prompt is untouched")
        assertEquals(1, retried.count { it is AgentEvent.RunStarted }, "the retried run is the run the prompt opened")
        val recovered = assertIs<AgentEvent.RunFinished>(retried.last())
        assertEquals(RunResult.Status.COMPLETED, recovered.status, "error: ${recovered.error}")
        assertContains(assertNotNull(recovered.finalMessage), TOKEN)
        assertEquals(1, retried.count { it is AgentEvent.RunFinished }, "the failed run_finished is gone")
        assertEquals(1, retried.count { it is AgentEvent.LlmCallStarted }, "the call sits where the failed one was")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }

    @Test
    @DisplayName(
        "A subagent whose response is cut off fails its run, and the parent's prompt_subagent call fails with it " +
            "instead of returning an empty answer"
    )
    fun cutOffSubagentFailsThePromptCall() = withFaultyServer { http ->
        writeHarness(tempDir.resolve("worker"))
        val manager = writeHarness(tempDir.resolve("manager"), subagents = mapOf("worker" to "../worker"))
        val rootId = http.createSession(manager.toString(), localWorkspace(tempDir, "cut-off")).id
        router.script(
            Reply.toolCalls(toolCall("spawn", "spawn_subagent", "name" to "w", "type" to "worker")),
            Reply.toolCalls(toolCall("prompt", "prompt_subagent", "name" to "w", "message" to "Do the work.")),
            Reply.ended(FinishReason.LENGTH),
            Reply.ended(FinishReason.STOP, "The worker failed."),
        )

        http.prompt(rootId, "Delegate the work.")

        val rootEvents = http.awaitRunEnd(rootId)
        val childId = rootEvents.filterIsInstance<AgentEvent.SubagentSpawned>().single().sessionId
        val child = assertIs<AgentEvent.RunFinished>(http.events(childId).last())
        assertEquals(RunResult.Status.ERROR, child.status, "a cut-off response is not a completed run")
        assertContains(assertNotNull(child.error).message, "finish_reason 'length'")
        assertNull(child.finalMessage)
        val promptCall = rootEvents.filterIsInstance<AgentEvent.ToolCallFinished>().single { it.callId == "prompt" }
        assertEquals(AgentEvent.ToolCallFinished.Outcome.ERROR, promptCall.outcome, "the parent sees the failure")
        assertContains(
            promptCall.resultText,
            "run ended as ERROR — Model response ended with finish_reason 'length'. ",
            message = "the parent model learns why, in the words the child's log records",
        )
        val root = assertIs<AgentEvent.RunFinished>(rootEvents.last())
        assertEquals(RunResult.Status.COMPLETED, root.status, "the parent carries on: ${root.error}")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(childId).status)
    }

    @Test
    @DisplayName("A body that is not JSON ends the run in error with the failure recorded and no call left dangling")
    fun malformedBodyFailsTheRun() = withFaultyServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "malformed")).id
        router.script(Reply.Malformed)

        http.prompt(id, "Reply with the single word: ready.")

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.ERROR, finished.status)
        val error = assertNotNull(finished.error, "the decoding failure is recorded")
        assertTrue(error.message.isNotBlank(), "the recorded failure says what went wrong")
        assertNull(error.statusCode, "a 200 carries no failing status")
        assertTrue(events.none { it is AgentEvent.LlmCallRetried }, "an unparseable 200 is not transient")
        assertTrue(events.none { it is AgentEvent.LlmCallFinished }, "no completion was decoded")
        assertTrue(events.none { it is AgentEvent.ToolCallStarted }, "no call was ever started, so none dangles")
        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }
}

private fun toolCall(id: String, tool: String, vararg arguments: Pair<String, String>): ToolCall {
    val json = JsonObject(arguments.associate { (key, value) -> key to JsonPrimitive(value) })
    return ToolCall(id, ToolCallFunction(tool, json))
}

private const val TOKEN: String = "plugh-7731"
