package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import io.ktor.client.request.delete
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

/**
 * The server's interaction surface driven end to end: a real server
 * process started from the installed distribution, real HTTP, real SSE,
 * and a real model behind it.
 */
class ServerInteractionLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Create, prompt, stream, complete: the whole path over real HTTP against the packaged server")
    fun createPromptStreamComplete() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        Path.of(workspace.workspace).resolve("token.txt").writeText("$TOKEN\n")
        val session = http.createSession(liveHarness(tempDir), workspace)

        assertEquals(SessionStatus.IDLE, session.status)
        assertEquals(SessionStatus.RUNNING, http.prompt(session.id, READ_TOKEN_PROMPT).status)

        val events = http.streamEvents(session.id)

        val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
        assertEquals(RunResult.Status.COMPLETED, finished.status)
        assertContains(
            assertNotNull(finished.finalMessage),
            TOKEN,
            ignoreCase = true,
            message = "the planted token can only reach the answer through a tool call in the workspace",
        )
        assertIs<AgentEvent.SessionStarted>(events.first().event)
        assertTrue(events.any { it.event is AgentEvent.ToolCallFinished }, "the token needs a tool call to be found")
        assertEquals(
            List(events.size) { it.toLong() },
            events.map { it.id },
            "the SSE ids must be the log's gapless sequence ids",
        )
        assertEquals(SessionStatus.IDLE, http.sessionInfo(session.id).status)
        assertEquals(finished.turnsUsed, http.sessionInfo(session.id).lastRun?.turnsUsed)
    }

    @Test
    @DisplayName("Reconnecting with Last-Event-ID resumes strictly after it")
    fun lastEventIdReplaysExactlyTheTail() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "reconnect")).id
        http.prompt(id, "Reply with the single word: ready.")

        val full = http.streamEvents(id)
        assertTrue(full.size > 2, "a run must log more than two events")

        val replay = http.streamEvents(id, afterSequenceId = full[1].id)

        assertEquals(full.drop(2), replay)
    }

    @Test
    @DisplayName("Two concurrent subscribers see the same stream: same events, same order")
    fun concurrentSubscribersSeeTheSameStream() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "subscribers")).id
        coroutineScope {
            val first = async { http.streamEvents(id) }
            val second = async { http.streamEvents(id) }
            http.prompt(id, "Reply with the single word: ready.")

            assertEquals(first.await(), second.await())
            assertTrue(first.await().isNotEmpty())
        }
    }

    @Test
    @DisplayName("Deleting a session ends its live event streams")
    fun deleteEndsLiveStreams() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "deleted")).id
        http.prompt(id, "Reply with the single word: ready.")
        http.awaitRunEnd(id)

        coroutineScope {
            val subscribed = CompletableDeferred<Unit>()
            // Nothing on this side ends the stream, so only the delete can —
            // and the wait is the suite's, so a delete that leaves the
            // subscriber parked fails the case instead of hanging it.
            val watcher = async { http.streamEvents(id, until = untilTheServerEnds(subscribed)) }
            subscribed.await()
            http.delete("/v1/sessions/$id")

            assertTrue(
                watcher.await().isNotEmpty(),
                "the parked subscriber must have been released, having seen the log",
            )
        }
    }
}

/** An end condition that never matches; it only reports through [subscribed] that the log is arriving. */
private fun untilTheServerEnds(subscribed: CompletableDeferred<Unit>): (AgentEvent) -> Boolean = {
    subscribed.complete(Unit)
    false
}

/** The planted needle the server's agent can only obtain from the workspace. */
private const val TOKEN: String = "plugh-5507"

private const val READ_TOKEN_PROMPT: String =
    "Read the file token.txt in the workspace with the bash tool and reply with the token it contains."
