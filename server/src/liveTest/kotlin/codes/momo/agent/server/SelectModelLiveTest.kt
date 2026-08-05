package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.harness.harnessPath
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Selecting a model against the real server process: no case reaches a chat completion, so all cost milliseconds. */
class SelectModelLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Selecting on a live session updates its info and streams model_selected to a parked subscriber")
    fun selectOnALiveSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))

            coroutineScope {
                val watcher = async {
                    http.streamEvents(created.id, until = { it is AgentEvent.ModelSelected })
                }
                val selected = http.selectModel(created.id, "picked-model", ReasoningEffort.HIGH)
                assertEquals(ModelSelection("picked-model", ReasoningEffort.HIGH), selected.modelSelection)
                assertEquals(SessionStatus.IDLE, selected.status)
                val event = assertIs<AgentEvent.ModelSelected>(watcher.await().last().event)
                assertEquals("picked-model", event.model)
                assertEquals(ReasoningEffort.HIGH, event.reasoningEffort)
            }

            assertEquals(
                ModelSelection("picked-model", ReasoningEffort.HIGH),
                http.sessionInfo(created.id).modelSelection,
            )
        }
    }

    @Test
    @DisplayName("Selecting on a closed session appends to its stored log without resuming it")
    fun selectOnAClosedSession() {
        withLiveServer { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            http.closeSession(created.id)

            coroutineScope {
                // Subscribed while dormant: the tail must serve the appended event.
                val watcher = async {
                    http.streamEvents(created.id, until = { it is AgentEvent.ModelSelected })
                }
                val selected = http.selectModel(created.id, "picked-model")
                assertEquals(ModelSelection("picked-model"), selected.modelSelection)
                assertEquals(SessionStatus.CLOSED, selected.status, "a selection must not resume the session")
                val event = assertIs<AgentEvent.ModelSelected>(watcher.await().last().event)
                assertEquals("picked-model", event.model)
                assertEquals(1L, event.sequenceId, "appended gaplessly after session_started")
            }

            assertEquals(SessionStatus.CLOSED, http.sessionInfo(created.id).status)
        }
    }
}
