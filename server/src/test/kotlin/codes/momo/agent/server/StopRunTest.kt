package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StopRunTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Stopping a run in flight ends it as stopped and leaves the session idle and promptable at once")
    fun stopEndsAnInFlightRunAndKeepsTheSessionAttached() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = localWorkspace(tempDir)

        // A server that never answers: the run stays in flight until stopped.
        scriptedServer().use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                SessionRegistry(dataDir, client).use { registry ->
                    runBlocking {
                        val id = registry.create(harness, workspace).id
                        registry.startRun(id, "hang forever", TEST_RUN_SETTINGS)
                        registry.awaitRunStart(id)
                        assertEquals(SessionStatus.RUNNING, registry.info(id).status)

                        registry.stopRun(id)

                        // The stop's own return already saw the run's end logged.
                        val logged = SessionStore(dataDir).readEvents(id).last()
                        assertEquals(RunResult.Status.STOPPED, assertIs<AgentEvent.RunFinished>(logged).status)

                        // Idle, not closed: the runtime and its one environment
                        // stayed attached, so the next prompt rebuilds nothing.
                        // Awaited, since the server's own claim on the run it
                        // just ended can outlive the stop's return.
                        registry.awaitRunEnd(id)
                        assertEquals(SessionStatus.IDLE, registry.info(id).status)

                        registry.startRun(id, "carry on", TEST_RUN_SETTINGS)
                        assertEquals(SessionStatus.RUNNING, registry.info(id).status)
                    }
                }
            }
        }
    }
}
