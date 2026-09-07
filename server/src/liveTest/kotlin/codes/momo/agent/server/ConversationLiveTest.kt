package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.deleteSession
import codes.momo.agent.server.rig.liveChatModel
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.sessionInfoResponse
import codes.momo.agent.server.rig.streamEvents
import codes.momo.agent.server.rig.withChangeStream
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.ModelSelection
import codes.momo.agent.server.session.SessionStatus
import codes.momo.agent.tool.ToolRegistry
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    @DisplayName("Create, prompt, stream, complete: the token comes out of the workspace, and the next run recalls it")
    fun theFullPathAndAContinuation() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        Path.of(workspace).resolve("secret.txt").writeText("$TOKEN\n")
        val session = http.createSession(liveHarness(tempDir), workspace)
        assertEquals(SessionStatus.IDLE, session.status)

        val events = http.withChangeStream { stream ->
            stream.signalled("a run starting") {
                assertEquals(SessionStatus.RUNNING, http.prompt(session.id, READ_AND_FLOOD_PROMPT).status)
            }

            val events = stream.signalled("a run ending") { http.streamEvents(session.id) }
            assertEquals(SessionStatus.IDLE, http.sessionInfo(session.id).status)
            events
        }

        val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(
            assertNotNull(finished.finalMessage),
            TOKEN,
            ignoreCase = true,
            message = "the planted token can only reach the answer through a tool call in the workspace",
        )
        assertIs<AgentEvent.SessionStarted>(events.first().event)
        assertEquals(
            List(events.size) { it.toLong() },
            events.map { it.id },
            "the SSE ids must be the log's gapless sequence ids",
        )
        val truncated = events.map { it.event }.filterIsInstance<AgentEvent.ToolCallFinished>().filter { it.truncated }
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
        val recalled = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(session.id, afterSequenceId = events.last().id).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, recalled.status, "error: ${recalled.error}")
        assertContains(
            assertNotNull(recalled.finalMessage),
            TOKEN,
            ignoreCase = true,
            message = "the second run must see the first run's transcript",
        )
    }

    @Test
    @DisplayName("Streams: Last-Event-ID replays exactly the tail, two subscribers agree, a delete ends them")
    fun theEventStreamContract() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "streams")).id

        val (first, second) = coroutineScope {
            val first = async { http.streamEvents(id) }
            val second = async { http.streamEvents(id) }
            http.prompt(id, READY_PROMPT)
            first.await() to second.await()
        }
        assertEquals(first, second, "two subscribers see the same events in the same order")
        assertTrue(first.size > 2, "a run must log more than two events")
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(first.last().event).status)

        val replay = http.streamEvents(id, afterSequenceId = first[1].id)
        assertEquals(first.drop(2), replay, "Last-Event-ID resumes strictly after the named event")

        http.awaitRunEnd(id)
        coroutineScope {
            val subscribed = CompletableDeferred<Unit>()

            val watcher = async { http.streamEvents(id, until = untilTheServerEnds(subscribed)) }
            subscribed.await()
            http.deleteSession(id)
            assertTrue(
                watcher.await().isNotEmpty(),
                "the parked subscriber must have been released, having seen the log"
            )
        }
        assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(id).status, "a deleted session is unknown")
    }
}

private fun untilTheServerEnds(subscribed: CompletableDeferred<Unit>): (AgentEvent) -> Boolean = {
    subscribed.complete(Unit)
    false
}

private const val TOKEN: String = "plugh-5507"

private const val READ_AND_FLOOD_PROMPT: String =
    "Two steps, using the bash tool. First, run `cat secret.txt` and note the token it prints. " +
        "Second, run exactly this command verbatim: `yes x | head -c 200000` — its output is deliberately " +
        "large and gets cut off; that is fine, do not retry or shorten it. Then reply with one sentence " +
        "containing the token verbatim."

private const val READY_PROMPT: String = "Reply with the single word: ready."
