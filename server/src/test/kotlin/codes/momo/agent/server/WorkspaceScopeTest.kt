package codes.momo.agent.server

import codes.momo.agent.harness.harnessPath
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session belongs to the workspace folder it works in, and that folder
 * scopes every request: what one project's client may list, read and delete is
 * exactly its own sessions. One store holds them all, as one server serving
 * every window on the machine does.
 */
class WorkspaceScopeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("The listing holds the named workspace's sessions and nobody else's")
    fun listingIsScopedToItsWorkspace() {
        withSessionServer(tempDir) { http ->
            val projectA = localWorkspace(tempDir, "project-a")
            val projectB = localWorkspace(tempDir, "project-b")
            val inA = http.createSession(harnessPath(tempDir), projectA).id
            val alsoInA = http.createSession(harnessPath(tempDir), projectA).id
            val inB = http.createSession(harnessPath(tempDir), projectB).id

            assertEquals(setOf(inA, alsoInA), http.sessions(projectA).map { it.id }.toSet())
            assertEquals(listOf(inB), http.sessions(projectB).map { it.id })
            assertEquals(emptyList(), http.sessions(tempDir.resolve("untouched").toString()).map { it.id })
        }
    }

    @Test
    @DisplayName("A listing without a workspace is a 400 invalid_request: an unscoped listing is meaningless")
    fun aListingWithoutAWorkspaceIsRejected() {
        withSessionServer(tempDir) { http ->
            http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            val missing = http.sessionsResponse(workspace = null)
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertEquals("invalid_request", missing.body<ApiError>().code)

            assertEquals(HttpStatusCode.BadRequest, http.sessionsResponse("   ").status)
            assertEquals(HttpStatusCode.BadRequest, http.sessionsResponse("relative/path").status)
        }
    }

    @Test
    @DisplayName("Trailing slashes and dot segments name the same workspace, since a scope is compared normalized")
    fun spellingsOfOneFolderAreOneScope() {
        withSessionServer(tempDir) { http ->
            val workspace = localWorkspace(tempDir, "project")
            val id = http.createSession(harnessPath(tempDir), workspace).id

            val spellings = listOf(
                "${workspace.workspace}/",
                "${workspace.workspace}/.",
                "${workspace.workspace}/../project",
            )
            spellings.forEach { spelling ->
                assertEquals(listOf(id), http.sessions(spelling).map { it.id }, spelling)
            }
        }
    }

    @Test
    @DisplayName("Naming another workspace's session is a 404: a foreign session must look nonexistent")
    fun aForeignSessionIs404() {
        withSessionServer(tempDir) { http ->
            val projectA = localWorkspace(tempDir, "project-a")
            val projectB = localWorkspace(tempDir, "project-b")
            val inA = http.createSession(harnessPath(tempDir), projectA).id

            val foreign = http.sessionInfoResponse(inA, projectB.workspace)
            assertEquals(HttpStatusCode.NotFound, foreign.status)
            assertEquals("unknown_session", foreign.body<ApiError>().code)

            // Every verb under /{id}, not just the read: the guard is the route's.
            assertEquals(
                HttpStatusCode.NotFound,
                http.delete("/v1/sessions/$inA") { parameter("workspace", projectB.workspace) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                http.post("/v1/sessions/$inA/stop") { parameter("workspace", projectB.workspace) }.status,
            )

            // Untouched by the refused calls, and still its own workspace's.
            assertEquals(HttpStatusCode.OK, http.sessionInfoResponse(inA, projectA.workspace).status)
        }
    }

    @Test
    @DisplayName("A foreign workspace cannot even subscribe to a session's events: the guard is the route's")
    fun aForeignSessionsEventStreamIs404() {
        withSessionServer(tempDir) { http ->
            val projectA = localWorkspace(tempDir, "project-a")
            val projectB = localWorkspace(tempDir, "project-b")
            val id = http.createSession(harnessPath(tempDir), projectA).id

            // The whole reason the scope is enforced by a route-scoped plugin
            // rather than per handler: an SSE handler runs with the 200 already
            // committed, too late for a 404. A per-handler check would leave
            // this one route open and pass every case above.
            val foreign = http.get("/v1/sessions/$id/events") { parameter("workspace", projectB.workspace) }
            assertEquals(HttpStatusCode.NotFound, foreign.status)
            assertEquals("unknown_session", foreign.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A session whose stored workspace cannot be read is in nobody's scope, and reads as unknown")
    fun anUnreadableSessionIsOutsideEveryScope() {
        withSessionServer(tempDir) { http ->
            val workspace = localWorkspace(tempDir, "project")
            val id = http.createSession(harnessPath(tempDir), workspace).id
            tempDir.resolve("data/sessions/$id/session.json").writeText("not json")

            // Unreadable state cannot say which workspace it belongs to, so a
            // scoped request must not report it either — that would leak the
            // existence of a session the scope is there to hide. Diagnosing it
            // loudly is the unscoped read's job, which is also the repair path.
            assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(id, workspace.workspace).status)
            assertEquals(
                HttpStatusCode.InternalServerError,
                http.get("/v1/sessions/$id").status,
                "unscoped, a corrupt session still reports itself",
            )
        }
    }

    @Test
    @DisplayName("A session named with its own workspace is served, and one named with none is served too")
    fun anInScopeOrUnscopedSessionIsServed() {
        withSessionServer(tempDir) { http ->
            val workspace = localWorkspace(tempDir, "project")
            val id = http.createSession(harnessPath(tempDir), workspace).id

            assertEquals(HttpStatusCode.OK, http.sessionInfoResponse(id, workspace.workspace).status)
            // Unscoped stays legal: a curl session and a server test name a
            // session by ID alone, and it is the client that always scopes.
            assertEquals(HttpStatusCode.OK, http.get("/v1/sessions/$id").status)
        }
    }

    @Test
    @DisplayName("A subagent is in its root's workspace, so a child resolves through the tree it belongs to")
    fun aChildIsScopedByItsRoot() {
        withSpawnedChild(tempDir) { http, _, childId ->
            val ownWorkspace = localWorkspace(tempDir).workspace
            val other = tempDir.resolve("elsewhere").createDirectories().toString()

            assertEquals(HttpStatusCode.OK, http.sessionInfoResponse(childId, ownWorkspace).status)
            assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(childId, other).status)
        }
    }

    @Test
    @DisplayName("A session whose workspace folder is gone is still listed and still deletable")
    fun aDeletedWorkspaceFolderLeavesItsSessionsReachable() {
        withSessionServer(tempDir) { http ->
            val workspace = localWorkspace(tempDir, "doomed")
            val id = http.createSession(harnessPath(tempDir), workspace).id
            // Comparison is lexical and touches no filesystem, which is what
            // keeps a session reachable after its folder goes.
            Path.of(workspace.workspace).toFile().deleteRecursively()

            assertContains(http.sessions(workspace).map { it.id }, id)
            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/$id").status)
            assertTrue(http.sessions(workspace).none { it.id == id })
        }
    }
}
