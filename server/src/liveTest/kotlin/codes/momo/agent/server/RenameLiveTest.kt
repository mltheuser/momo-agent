package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.harness.harnessPath
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Renaming against the real server process: no case reaches a chat completion, so all cost milliseconds. */
class RenameLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Renaming a live session updates its info and streams session_renamed to a parked subscriber")
    fun renameOnALiveSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            coroutineScope {
                val watcher = async {
                    http.streamEvents(created.id, until = { it is AgentEvent.SessionRenamed })
                }
                val renamed = http.renameSession(created.id, "Chosen title")
                assertEquals("Chosen title", renamed.title)
                assertEquals(SessionStatus.IDLE, renamed.status)
                val event = assertIs<AgentEvent.SessionRenamed>(watcher.await().last().event)
                assertEquals("Chosen title", event.title)
            }

            assertEquals("Chosen title", http.sessionInfo(created.id).title)
            assertEquals(
                "Chosen title",
                http.sessions(localWorkspace(tempDir)).single { it.id == created.id }.title,
            )
        }
    }

    @Test
    @DisplayName("Renaming a closed session appends to its stored log without resuming it")
    fun renameOnAClosedSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            http.closeSession(created.id)

            coroutineScope {
                // Subscribed while dormant: the tail must serve the appended event.
                val watcher = async {
                    http.streamEvents(created.id, until = { it is AgentEvent.SessionRenamed })
                }
                val renamed = http.renameSession(created.id, "Renamed while closed")
                assertEquals("Renamed while closed", renamed.title)
                assertEquals(SessionStatus.CLOSED, renamed.status, "a rename must not resume the session")
                val event = assertIs<AgentEvent.SessionRenamed>(watcher.await().last().event)
                assertEquals("Renamed while closed", event.title)
                assertEquals(1L, event.sequenceId, "appended gaplessly after session_started")
            }

            assertEquals(SessionStatus.CLOSED, http.sessionInfo(created.id).status)
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A rename on an unknown session is a 404 unknown_session")
    fun unknownSessionIs404() {
        withLiveServer { http ->
            val rename = http.renameResponse("no-such-id", "title")
            assertEquals(HttpStatusCode.NotFound, rename.status)
            assertEquals("unknown_session", rename.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A blank title is a 400 invalid_request leaving the stored title untouched")
    fun blankTitleIsRejected() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.renameResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals("harness", http.sessionInfo(id).title)
        }
    }
}
