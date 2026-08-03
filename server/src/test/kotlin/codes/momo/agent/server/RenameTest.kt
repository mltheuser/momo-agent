package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.assistantResponse
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What a rename does to a session's stored log — the half of the contract that needs a run to have happened. */
class RenameTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A prompt after a closed-session rename resumes cleanly, keeping the title and a gapless log")
    fun promptResumesPastAClosedRename() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "run done")),
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
            assertIs<AgentEvent.RunFinished>(events.last(), "the resumed run's end closes the log")
            events.forEachIndexed { index, event ->
                assertEquals(index.toLong(), event.sequenceId, "the resumed run numbers on past the appended rename")
            }
        }
    }

    @Test
    @DisplayName("updatedAtMillis is the last event's timestamp: both a run and a rename bump it")
    fun updatedAtTracksTheLastEvent() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
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
        }
    }
}
