package codes.momo.agent.server

import codes.momo.agent.labeledContainers
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContainerSessionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun containerWorkspace(name: String): EnvironmentSpec.Container =
        EnvironmentSpec.Container(DEBIAN_IMAGE, tempDir.resolve(name).createDirectories().toString())

    @Test
    @DisplayName("A container session's lifecycle owns its container: created on create, removed on close")
    fun containerSessionLifecycleLeavesNoContainerBehind() {
        val before = labeledContainers()
        withSessionServer(tempDir) { http ->
            val created = http.createSession(harnessPath(tempDir), containerWorkspace("workspace"))
            assertEquals(SessionStatus.IDLE, created.status)
            assertEquals(1, (labeledContainers() - before).size, "expected exactly one new labeled container")

            val closed = http.post("/v1/sessions/${created.id}/close").body<SessionInfo>()
            assertEquals(SessionStatus.CLOSED, closed.status)
            assertEquals(before, labeledContainers(), "close() must remove the session's container")

            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/${created.id}").status)
        }
        assertEquals(before, labeledContainers())
    }

    @Test
    @DisplayName("Two container sessions are isolated: each owns its container, closing one spares the other")
    fun twoContainerSessionsAreIsolated() {
        val before = labeledContainers()
        withSessionServer(tempDir) { http ->
            val first = http.createSession(harnessPath(tempDir), containerWorkspace("workspace-a"))
            val afterFirst = labeledContainers() - before
            val second = http.createSession(harnessPath(tempDir), containerWorkspace("workspace-b"))
            val secondContainer = labeledContainers() - before - afterFirst

            assertNotEquals(first.id, second.id)
            assertEquals(1, afterFirst.size)
            assertEquals(1, secondContainer.size)

            http.post("/v1/sessions/${first.id}/close")
            assertEquals(before + secondContainer, labeledContainers(), "closing one session spares the other")
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/${second.id}").body<SessionInfo>().status)

            http.post("/v1/sessions/${second.id}/close")
            assertEquals(before, labeledContainers())
        }
    }

    private companion object {

        /** Small pinned image, matching the lib container suite. */
        const val DEBIAN_IMAGE = "debian:12-slim"
    }
}
