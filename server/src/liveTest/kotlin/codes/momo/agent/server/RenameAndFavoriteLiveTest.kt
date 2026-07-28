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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Renaming and favoriting against the real server process: neither reaches a
 * chat completion, so both cost the suite milliseconds.
 */
class RenameAndFavoriteLiveTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Rename ───────────────────────────────────────────────────────

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
            assertEquals("Chosen title", http.sessions().single { it.id == created.id }.title)
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

    // ─── Favorite ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The favorite flag defaults to false and toggles on a live session, visible in get and list")
    fun favoriteTogglesOnALiveSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            assertFalse(created.favorite)

            val favorited = http.setFavorite(created.id, true)
            assertTrue(favorited.favorite)
            assertEquals(SessionStatus.IDLE, favorited.status)
            assertTrue(http.sessionInfo(created.id).favorite)
            assertTrue(http.sessions().single { it.id == created.id }.favorite)

            assertFalse(http.setFavorite(created.id, false).favorite)
        }
    }

    @Test
    @DisplayName("Favoriting a closed session leaves it closed and appends nothing to its event log")
    fun favoriteOnAClosedSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            http.closeSession(created.id)

            val favorited = http.setFavorite(created.id, true)
            assertTrue(favorited.favorite)
            assertEquals(SessionStatus.CLOSED, favorited.status, "a favorite toggle must not resume the session")

            val events = SessionStore(sharedLiveServer.dataDir).readEvents(created.id)
            assertIs<AgentEvent.SessionStarted>(events.single(), "favorite is metadata, never an event")
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Rename and favorite on an unknown session are 404 unknown_session")
    fun unknownSessionIs404() {
        withLiveServer { http ->
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
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.renameResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals("harness", http.sessionInfo(id).title)
        }
    }
}
