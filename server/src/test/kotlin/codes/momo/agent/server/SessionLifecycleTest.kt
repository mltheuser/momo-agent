package codes.momo.agent.server

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLifecycleTest {

    @TempDir
    lateinit var tempDir: Path

    private fun workspace(name: String = "workspace"): EnvironmentSpec.Local =
        EnvironmentSpec.Local(tempDir.resolve(name).createDirectories().toString())

    // ─── Happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("A created session is inspectable: get and list report the derived fields")
    fun createGetAndListReportTheSession() {
        val harness = harnessPath(tempDir)
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harness, workspace())

            assertTrue(created.id.isNotBlank())
            assertEquals("harness", created.title)
            assertEquals("test-model", created.model)
            assertEquals(harness, created.harnessPath)
            assertEquals(workspace(), created.environment)
            assertEquals(SessionStatus.IDLE, created.status)
            assertTrue(created.createdAtMillis > 0)
            assertNull(created.lastPrompt, "no prompt ran yet")
            assertNull(created.pendingQuestion)

            assertEquals(created, http.get("/v1/sessions/${created.id}").body<SessionInfo>())
            assertEquals(listOf(created), http.get("/v1/sessions").body<List<SessionInfo>>())
        }
    }

    @Test
    @DisplayName("Close parks the session — still listed, resumable — and closing again is a no-op success")
    fun closeParksAndIsIdempotent() {
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), workspace())

            val closed = http.post("/v1/sessions/${created.id}/close").body<SessionInfo>()
            assertEquals(SessionStatus.CLOSED, closed.status)
            assertEquals(listOf(closed), http.get("/v1/sessions").body<List<SessionInfo>>())

            val closedAgain = http.post("/v1/sessions/${created.id}/close")
            assertEquals(HttpStatusCode.OK, closedAgain.status)
            assertEquals(SessionStatus.CLOSED, closedAgain.body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("Delete removes the session and its stored artifacts; subsequent lookups 404")
    fun deleteRemovesTheSession() {
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), workspace())

            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/${created.id}").status)

            val lookup = http.get("/v1/sessions/${created.id}")
            assertEquals(HttpStatusCode.NotFound, lookup.status)
            assertEquals("unknown_session", lookup.body<ApiError>().code)
            assertEquals(emptyList(), http.get("/v1/sessions").body<List<SessionInfo>>())
            assertEquals(emptyList(), SessionStore(tempDir.resolve("data")).sessionIds())
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("An invalid harness path is a 400 naming the folder")
    fun invalidHarnessPathIsRejected() {
        withSessionServer(tempDir) { http ->
            val missing = tempDir.resolve("no-such-harness").toString()

            val response = http.createSessionResponse(CreateSessionRequest(missing, workspace()))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.body<ApiError>()
            assertEquals("invalid_harness", error.code)
            assertContains(error.message, missing)
        }
    }

    @Test
    @DisplayName("An unknown environment type is a 400 invalid_request")
    fun invalidEnvironmentSpecIsRejected() {
        withSessionServer(tempDir) { http ->
            val body = """
                {"harnessPath": ${Json.encodeToString(harnessPath(tempDir))},
                 "environment": {"type": "martian", "workspace": "/tmp"}}
            """.trimIndent()

            val response = http.post("/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A missing workspace folder is a 400 invalid_environment")
    fun missingWorkspaceIsRejected() {
        withSessionServer(tempDir) { http ->
            val missing = tempDir.resolve("no-such-workspace").toString()

            val response = http.createSessionResponse(
                CreateSessionRequest(harnessPath(tempDir), EnvironmentSpec.Local(missing)),
            )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.body<ApiError>()
            assertEquals("invalid_environment", error.code)
            assertContains(error.message, missing)
        }
    }

    @Test
    @DisplayName("A session with unreadable stored state drops out of the list; get reports it loudly")
    fun corruptSessionDoesNotPoisonTheList() {
        withSessionServer(tempDir) { http ->
            val healthy = http.createSession(harnessPath(tempDir), workspace("workspace-a"))
            val corrupt = http.createSession(harnessPath(tempDir), workspace("workspace-b"))
            tempDir.resolve("data/sessions/${corrupt.id}/session.json").writeText("not json")

            assertEquals(listOf(healthy), http.get("/v1/sessions").body<List<SessionInfo>>())
            val lookup = http.get("/v1/sessions/${corrupt.id}")
            assertEquals(HttpStatusCode.InternalServerError, lookup.status)
            assertEquals("corrupt_session", lookup.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("Operations on an unknown session ID are 404 unknown_session")
    fun unknownSessionIs404() {
        withSessionServer(tempDir) { http ->
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/no-such-id").status)
            assertEquals(HttpStatusCode.NotFound, http.post("/v1/sessions/no-such-id/close").status)
            assertEquals(HttpStatusCode.NotFound, http.delete("/v1/sessions/no-such-id").status)
        }
    }

    // ─── Concurrency ──────────────────────────────────────────────────

    @Test
    @DisplayName("Two sessions run side by side without interference and close independently")
    fun twoSessionsAreIndependent() {
        val harness = harnessPath(tempDir)
        withSessionServer(tempDir) { http ->
            val (first, second) = coroutineScope {
                listOf(
                    async { http.createSession(harness, workspace("workspace-a")) },
                    async { http.createSession(harness, workspace("workspace-b")) },
                ).awaitAll()
            }

            assertNotEquals(first.id, second.id)
            assertEquals(2, http.get("/v1/sessions").body<List<SessionInfo>>().size)

            http.post("/v1/sessions/${first.id}/close")
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/${first.id}").body<SessionInfo>().status)
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/${second.id}").body<SessionInfo>().status)
        }
    }
}
