package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.deleteSession
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.eventsResponse
import codes.momo.agent.server.rig.liveChatModel
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.withChangeStream
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.ModelSelection
import codes.momo.agent.server.session.SessionStatus
import codes.momo.agent.tool.ToolRegistry
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Create, prompt, complete: the token comes out of the workspace, and the next run recalls it")
    fun theFullPathAndAContinuation() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        Path.of(workspace).resolve("secret.txt").writeText("$TOKEN\n")
        val session = http.createSession(liveHarness(tempDir), workspace)
        assertEquals(SessionStatus.IDLE, session.status)

        val events = http.withChangeStream { stream ->
            stream.signalled("a run starting") {
                assertEquals(SessionStatus.RUNNING, http.prompt(session.id, READ_AND_FLOOD_PROMPT).status)
            }
            val before = stream.received()
            val events = stream.signalled("a run ending") { http.awaitRunEnd(session.id) }
            assertEquals(SessionStatus.IDLE, http.sessionInfo(session.id).status)
            assertTrue(stream.received() - before > 1, "the run's events ring the change stream, not only its end")
            events
        }

        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(
            assertNotNull(finished.finalMessage),
            TOKEN,
            ignoreCase = true,
            message = "the planted token can only reach the answer through a tool call in the workspace",
        )
        assertIs<AgentEvent.SessionStarted>(events.first())
        assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId }, "the log has gaps")
        val truncated = events.filterIsInstance<AgentEvent.ToolCallFinished>().filter { it.truncated }
        assertEquals(1, truncated.size, "exactly the flood command's result is cut to the model-facing cap")
        assertContains(
            truncated.single().resultText,
            ToolRegistry.truncationMarker(ToolRegistry.MAX_RESULT_CHARS),
            message = "a truncated result ends in the marker naming the applied limit",
        )
        val info = http.sessionInfo(session.id)
        assertEquals(finished.turnsUsed, info.lastRun?.turnsUsed, "lastRun reports the completed run's consumption")
        assertEquals(ModelSelection(liveChatModel), info.modelSelection, "the run's model is the shown selection")

        Path.of(workspace).resolve("secret.txt").toFile().delete()
        http.prompt(session.id, "Without using any tools, repeat the exact token you read earlier.")
        val recalled = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(session.id).last())
        assertEquals(RunResult.Status.COMPLETED, recalled.status, "error: ${recalled.error}")
        assertContains(
            assertNotNull(recalled.finalMessage),
            TOKEN,
            ignoreCase = true,
            message = "the second run must see the first run's transcript",
        )
    }

    @Test
    @DisplayName("The log is served whole and settled, two reads agree, and a delete makes it a 404")
    fun theLogAsAWhole() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "whole")).id
        assertEquals(listOf(0L), http.events(id).map { it.sequenceId }, "a fresh log holds its session_started")

        http.prompt(id, READY_PROMPT)
        val log = http.awaitRunEnd(id)
        assertTrue(log.size > 2, "a run must log more than two events")
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(log.last()).status)
        assertEquals(log, http.events(id), "two reads of an idle log agree")

        http.deleteSession(id)
        val gone = http.eventsResponse(id)
        assertEquals(HttpStatusCode.NotFound, gone.status, "a deleted session's log is unknown")
        assertEquals("unknown_session", gone.body<ApiError>().code)
    }
}

private const val TOKEN: String = "plugh-5507"

private const val READ_AND_FLOOD_PROMPT: String =
    "Two steps, using the bash tool. First, run `cat secret.txt` and note the token it prints. " +
        "Second, run exactly this command verbatim: `yes x | head -c 200000` — its output is deliberately " +
        "large and gets cut off; that is fine, do not retry or shorten it. Then reply with one sentence " +
        "containing the token verbatim."

private const val READY_PROMPT: String = "Reply with the single word: ready."
