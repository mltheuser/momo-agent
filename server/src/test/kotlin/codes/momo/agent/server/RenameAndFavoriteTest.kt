package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.assistantResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RenameAndFavoriteTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Rename ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Renaming a live session updates its info and streams session_renamed to a parked subscriber")
    fun renameOnALiveSession() {
        withSessionServer(tempDir) { http ->
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

            assertEquals("Chosen title", http.get("/v1/sessions/${created.id}").body<SessionInfo>().title)
            assertEquals("Chosen title", http.get("/v1/sessions").body<List<SessionInfo>>().single().title)
        }
    }

    @Test
    @DisplayName("Renaming a closed session appends to its stored log without resuming it")
    fun renameOnAClosedSession() {
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            http.post("/v1/sessions/${created.id}/close")

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

            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/${created.id}").body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("A prompt after a closed-session rename resumes cleanly, keeping the title and a gapless log")
    fun promptResumesPastAClosedRename() {
        withScriptedSessionServer(
            tempDir,
            assistantResponse(finishReason = "stop", text = "first run done"),
            assistantResponse(finishReason = "stop", text = "resumed run done"),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            http.awaitRunEnd(id)
            http.post("/v1/sessions/$id/close")
            http.renameSession(id, "Renamed while closed")

            http.prompt(id, "again")
            http.awaitRunEnd(id)

            assertEquals("Renamed while closed", http.get("/v1/sessions/$id").body<SessionInfo>().title)
            val events = SessionStore(tempDir.resolve("data")).readEvents(id)
            assertEquals("resumed run done", assertIs<AgentEvent.RunFinished>(events.last()).finalMessage)
            events.forEachIndexed { index, event ->
                assertEquals(index.toLong(), event.sequenceId, "the resumed run numbers on past the appended rename")
            }
        }
    }

    // ─── Favorite ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The favorite flag defaults to false and toggles on a live session, visible in get and list")
    fun favoriteTogglesOnALiveSession() {
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            assertFalse(created.favorite)

            val favorited = http.setFavorite(created.id, true)
            assertTrue(favorited.favorite)
            assertEquals(SessionStatus.IDLE, favorited.status)
            assertTrue(http.get("/v1/sessions/${created.id}").body<SessionInfo>().favorite)
            assertTrue(http.get("/v1/sessions").body<List<SessionInfo>>().single().favorite)

            assertFalse(http.setFavorite(created.id, false).favorite)
        }
    }

    @Test
    @DisplayName("Favoriting a closed session leaves it closed and appends nothing to its event log")
    fun favoriteOnAClosedSession() {
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            http.post("/v1/sessions/${created.id}/close")

            val favorited = http.setFavorite(created.id, true)
            assertTrue(favorited.favorite)
            assertEquals(SessionStatus.CLOSED, favorited.status, "a favorite toggle must not resume the session")

            val events = SessionStore(tempDir.resolve("data")).readEvents(created.id)
            assertIs<AgentEvent.SessionStarted>(events.single(), "favorite is metadata, never an event")
        }
    }

    // ─── Updated-at ───────────────────────────────────────────────────

    @Test
    @DisplayName("updatedAtMillis is the last event's timestamp: runs and renames bump it, favorites do not")
    fun updatedAtTracksTheLastEvent() {
        withScriptedSessionServer(tempDir, assistantResponse(finishReason = "stop", text = "done")) { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            assertEquals(created.createdAtMillis, created.updatedAtMillis, "a fresh log holds only session_started")

            http.prompt(created.id, "go")
            val lastEvent = http.streamEvents(created.id).last().event
            val afterRun = http.get("/v1/sessions/${created.id}").body<SessionInfo>()
            assertEquals(lastEvent.timestampMillis, afterRun.updatedAtMillis)

            val renamed = http.renameSession(created.id, "later")
            val renameEvent = assertIs<AgentEvent.SessionRenamed>(
                http.streamEvents(created.id, until = { it is AgentEvent.SessionRenamed }).last().event,
            )
            assertEquals(renameEvent.timestampMillis, renamed.updatedAtMillis)

            val favorited = http.setFavorite(created.id, true)
            assertEquals(renamed.updatedAtMillis, favorited.updatedAtMillis, "favorites are not events")
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Rename and favorite on an unknown session are 404 unknown_session")
    fun unknownSessionIs404() {
        withSessionServer(tempDir) { http ->
            val rename = http.renameResponse("no-such-id", "title")
            assertEquals(HttpStatusCode.NotFound, rename.status)
            assertEquals("unknown_session", rename.body<ApiError>().code)

            val favorite = http.favoriteResponse("no-such-id", true)
            assertEquals(HttpStatusCode.NotFound, favorite.status)
            assertEquals("unknown_session", favorite.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A blank title is a 400 invalid_request leaving the stored title untouched")
    fun blankTitleIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.renameResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals("harness", http.get("/v1/sessions/$id").body<SessionInfo>().title)
        }
    }
}
