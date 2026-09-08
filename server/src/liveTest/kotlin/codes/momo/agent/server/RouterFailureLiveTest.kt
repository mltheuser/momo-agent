package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.harnessPath
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.FaultyRouter
import codes.momo.agent.server.rig.FaultyRouter.Reply
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.streamEvents
import codes.momo.agent.server.session.SessionStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
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

        val events = http.streamEvents(id).map { it.event }
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
    @DisplayName("A completion reporting finish_reason 'error' ends the run in error and leaves the session promptable")
    fun finishReasonErrorFailsTheRun() = withFaultyServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "finish-reason")).id
        router.script(Reply.FinishReasonError)

        http.prompt(id, "Reply with the single word: ready.")

        val failed = http.streamEvents(id)
        val events = failed.map { it.event }
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.ERROR, finished.status)
        assertContains(assertNotNull(finished.error).message, "finish_reason 'error'")
        assertNull(finished.finalMessage, "a provider-side failure's text must not read as the model's answer")
        assertTrue(events.none { it is AgentEvent.LlmCallRetried }, "a 200 is never retried")
        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status, "the session stays idle")

        http.prompt(id, "Reply with exactly this token and nothing else: $TOKEN")
        val recovered = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = failed.last().id).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, recovered.status, "error: ${recovered.error}")
        assertContains(assertNotNull(recovered.finalMessage), TOKEN)
    }

    @Test
    @DisplayName("A body that is not JSON ends the run in error with the failure recorded and no call left dangling")
    fun malformedBodyFailsTheRun() = withFaultyServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "malformed")).id
        router.script(Reply.Malformed)

        http.prompt(id, "Reply with the single word: ready.")

        val events = http.streamEvents(id).map { it.event }
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

private const val TOKEN: String = "plugh-7731"
