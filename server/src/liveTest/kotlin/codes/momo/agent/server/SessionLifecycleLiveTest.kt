package codes.momo.agent.server

import codes.momo.agent.harness.harnessPath
import codes.momo.agent.harness.writeHarness
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The create/close/stop/delete contract and its 400/404/500 surface, against
 * the real server process. None of it reaches a chat completion, so these
 * cases cost the suite milliseconds and share its one process — each owning
 * only the sessions it creates.
 */
class SessionLifecycleLiveTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("A created session is inspectable: get and list report the derived fields")
    fun createGetAndListReportTheSession() {
        val harness = harnessPath(tempDir)
        withLiveServer { http ->
            val created = http.createSession(harness, localWorkspace(tempDir))

            assertTrue(created.id.isNotBlank())
            assertEquals("harness", created.title)
            assertEquals(harness, created.harnessPath)
            assertEquals(localWorkspace(tempDir), created.environment)
            assertEquals(SessionStatus.IDLE, created.status)
            assertTrue(created.createdAtMillis > 0)
            assertNull(created.lastRun, "no run happened yet")

            assertEquals(created, http.sessionInfo(created.id))
            assertEquals(created, http.ownSession(created.id))
        }
    }

    @Test
    @DisplayName("Close parks the session — still listed, resumable — and closing again is a no-op success")
    fun closeParksAndIsIdempotent() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            val closed = http.closeSession(created.id)
            assertEquals(SessionStatus.CLOSED, closed.status)
            assertEquals(closed, http.ownSession(created.id))

            val closedAgain = http.closeResponse(created.id)
            assertEquals(HttpStatusCode.OK, closedAgain.status)
            assertEquals(SessionStatus.CLOSED, closedAgain.body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("Stopping a session with no run in flight is an idempotent no-op success")
    fun stopWithNothingRunningIsANoOp() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            val stopped = http.stopResponse(created.id)

            assertEquals(HttpStatusCode.OK, stopped.status, stopped.bodyAsText())
            assertEquals(SessionStatus.IDLE, stopped.body<SessionInfo>().status)

            // Nor does a stop attach a dormant session, or park a live one.
            http.closeSession(created.id)
            val whileClosed = http.stopResponse(created.id)
            assertEquals(HttpStatusCode.OK, whileClosed.status)
            assertEquals(SessionStatus.CLOSED, whileClosed.body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("Delete removes the session and its stored artifacts; subsequent lookups 404")
    fun deleteRemovesTheSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/${created.id}").status)

            val lookup = http.get("/v1/sessions/${created.id}")
            assertEquals(HttpStatusCode.NotFound, lookup.status)
            assertEquals("unknown_session", lookup.body<ApiError>().code)
            assertTrue(http.sessions().none { it.id == created.id }, "a deleted session must leave the listing")
            assertTrue(
                created.id !in SessionStore(sharedLiveServer.dataDir).sessionIds(),
                "a deleted session must leave the store",
            )
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("An invalid harness path is a 400 naming the folder")
    fun invalidHarnessPathIsRejected() {
        withLiveServer { http ->
            val missing = tempDir.resolve("no-such-harness").toString()

            val response = http.createSessionResponse(CreateSessionRequest(missing, localWorkspace(tempDir)))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.body<ApiError>()
            assertEquals("invalid_harness", error.code)
            assertContains(error.message, missing)
        }
    }

    @Test
    @DisplayName("A harness referencing a broken subagent harness is a 400 naming the reference")
    fun brokenSubagentReferenceIsRejected() {
        withLiveServer { http ->
            val harness = writeHarness(
                tempDir.resolve("harness"),
                subagents = mapOf("helper" to "../nowhere"),
            ).toString()

            val response = http.createSessionResponse(CreateSessionRequest(harness, localWorkspace(tempDir)))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.body<ApiError>()
            assertEquals("invalid_harness", error.code)
            assertContains(error.message, "'helper'")
        }
    }

    @Test
    @DisplayName("An unknown environment type is a 400 invalid_request")
    fun invalidEnvironmentSpecIsRejected() {
        withLiveServer { http ->
            val body = """
                {"harnessPath": ${Json.encodeToString(harnessPath(tempDir))},
                 "environment": {"type": "martian", "workspace": "/tmp"}}
            """.trimIndent()

            val response = http.rawCreateSessionResponse(body)

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A create request still declaring a privilege is a 400 invalid_request, not a silently ignored field")
    fun declaredPrivilegeIsRejected() {
        withLiveServer { http ->
            // A well-formed value, so this pins the absence of the field rather
            // than the rejection of a bad one: a client that still asks for a
            // posture is told, instead of quietly getting whatever the host has.
            val body = """
                {"harnessPath": ${Json.encodeToString(harnessPath(tempDir))},
                 "environment": {"type": "local",
                                 "workspace": ${Json.encodeToString(localWorkspace(tempDir).workspace)},
                                 "privilege": "passwordless_sudo"}}
            """.trimIndent()

            val response = http.rawCreateSessionResponse(body)

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A live session reports the privilege its environment discovered; a closed one reports none")
    fun privilegeIsReportedWhileAttached() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            // Which posture the host grants is its business — CI, a root
            // container and a NOPASSWD dev box all differ — so only its
            // presence is asserted, never its value.
            assertNotNull(created.privilege, "a built environment must report the posture it found")

            assertNull(
                http.closeSession(created.id).privilege,
                "with no environment built there is nothing to report",
            )
        }
    }

    @Test
    @DisplayName("A missing workspace folder is a 400 invalid_environment")
    fun missingWorkspaceIsRejected() {
        withLiveServer { http ->
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
        withLiveServer { http ->
            val healthy = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "workspace-a"))
            val corrupt = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "workspace-b"))
            // Corruption is scoped to a session this test owns, so the shared
            // process stays sound for every other case.
            sharedLiveServer.dataDir.resolve("sessions/${corrupt.id}/session.json").writeText("not json")

            try {
                val listed = http.sessions().map { it.id }
                assertContains(listed, healthy.id, "an unreadable neighbour must not hide the healthy sessions")
                assertTrue(corrupt.id !in listed, "an unreadable session must not appear in the listing")
                val lookup = http.get("/v1/sessions/${corrupt.id}")
                assertEquals(HttpStatusCode.InternalServerError, lookup.status)
                assertEquals("corrupt_session", lookup.body<ApiError>().code)
            } finally {
                // Unconditionally, so a failed assertion cannot leave the
                // corrupt session to the cases sharing this process.
                http.delete("/v1/sessions/${corrupt.id}")
            }
        }
    }

    @Test
    @DisplayName("Operations on an unknown session ID are 404 unknown_session")
    fun unknownSessionIs404() {
        withLiveServer { http ->
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/no-such-id").status)
            assertEquals(HttpStatusCode.NotFound, http.stopResponse("no-such-id").status)
            assertEquals(HttpStatusCode.NotFound, http.closeResponse("no-such-id").status)
            assertEquals(HttpStatusCode.NotFound, http.delete("/v1/sessions/no-such-id").status)
        }
    }

    // ─── Concurrency ──────────────────────────────────────────────────

    @Test
    @DisplayName("Two sessions run side by side without interference and close independently")
    fun twoSessionsAreIndependent() {
        val harness = harnessPath(tempDir)
        withLiveServer { http ->
            val (first, second) = coroutineScope {
                listOf(
                    async { http.createSession(harness, localWorkspace(tempDir, "workspace-a")) },
                    async { http.createSession(harness, localWorkspace(tempDir, "workspace-b")) },
                ).awaitAll()
            }

            assertNotEquals(first.id, second.id)
            val listed = http.sessions().map { it.id }
            assertContains(listed, first.id)
            assertContains(listed, second.id)

            http.closeSession(first.id)
            assertEquals(SessionStatus.CLOSED, http.sessionInfo(first.id).status)
            assertEquals(SessionStatus.IDLE, http.sessionInfo(second.id).status)
        }
    }
}

/** The listing entry for [sessionId]; the shared process also holds every other case's sessions. */
private suspend fun HttpClient.ownSession(sessionId: String): SessionInfo =
    sessions().single { it.id == sessionId }

/** POSTs a create-session request whose body is [body] verbatim. */
private suspend fun HttpClient.rawCreateSessionResponse(body: String): HttpResponse =
    post("/v1/sessions") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
