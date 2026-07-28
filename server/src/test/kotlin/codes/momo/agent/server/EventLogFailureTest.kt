package codes.momo.agent.server

import codes.momo.agent.assistantResponse
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isWritable
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.assertEquals

class EventLogFailureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A session whose event log stopped persisting refuses new runs with a 500 event_log_failed")
    fun failedEventLogRefusesNewRuns() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.post("/v1/sessions/$id/close") // Detaches the log's open writer.
            val events = tempDir.resolve("data/sessions/$id/events.jsonl")
            events.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ))
            // Running as root (or similar) ignores POSIX permissions, so the
            // append this case needs to fail would succeed there.
            Assumptions.assumeFalse(
                events.isWritable(),
                "events.jsonl is still writable after dropping write permission (running as root?)",
            )

            // The resumed run cannot append: its events are lost and the log is poisoned.
            http.prompt(id, "work")
            http.awaitRunEnd(id)

            val refused = http.promptResponse(id, "again")
            assertEquals(HttpStatusCode.InternalServerError, refused.status)
            assertEquals("event_log_failed", refused.body<ApiError>().code)
        }
    }
}
