package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlm
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.bashCall
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.toolCallResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The ordering [codes.momo.agent.Agent.stop] promises: it returns once the
 * run it cut has ended, in every respect. Nothing observed over HTTP can show
 * this — a client sees the 200 and then waits for the event either way — so
 * the assertion has to read the stored log the instant the stop returns.
 */
class StopRunTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A stop returns only once the cut run's stopped end is already in the stored log")
    fun stopReturnsWithTheRunsEndAlreadyLogged() {
        val dataDir = tempDir.resolve("data")
        val store = SessionStore(dataDir)
        // Real time in a tool holds the run in flight without a fake that
        // withholds a reply: a stop landing before the run reaches its agent
        // would be the no-op this case cannot use.
        val fake = FakeLlm(onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "sleep 30"))))

        fake.client().use { client ->
            SessionRegistry(dataDir, client).use { registry ->
                runBlocking {
                    val id = registry.create(harnessPath(tempDir), localWorkspace(tempDir)).id
                    registry.startRun(id, "take your time", TEST_RUN_SETTINGS)
                    store.awaitLoggedEvent(id) { it is AgentEvent.ToolCallStarted }

                    registry.stopRun(id)

                    // The stop's own return already saw the run's end logged.
                    val logged = assertIs<AgentEvent.RunFinished>(store.readEvents(id).last())
                    assertEquals(RunResult.Status.STOPPED, logged.status)

                    // Idle, not closed: the runtime and its one environment
                    // stayed attached. Awaited, since the server's own claim
                    // on the run it just ended can outlive the stop's return.
                    registry.awaitRunEnd(id)
                    assertEquals(SessionStatus.IDLE, registry.info(id).status)
                }
            }
        }
    }
}
