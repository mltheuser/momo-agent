package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class CloseInFlightTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Closing a session with a run in flight aborts the run, then tears down and stays resumable")
    fun closeAbortsAnInFlightRun() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = localWorkspace(tempDir)

        // A server that never answers: the run stays in flight until aborted.
        scriptedServer().use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                SessionRegistry(dataDir, client).use { registry ->
                    runBlocking {
                        val id = registry.create(harness, workspace).id
                        registry.startRun(id, "hang forever", TEST_RUN_SETTINGS)
                        assertEquals(SessionStatus.RUNNING, registry.info(id).status)

                        registry.close(id)

                        assertEquals(SessionStatus.CLOSED, registry.info(id).status)

                        // The aborted run's log reloads into a usable session.
                        registry.startRun(id, "resume", TEST_RUN_SETTINGS)
                        assertEquals(SessionStatus.RUNNING, registry.info(id).status)
                    }
                }
            }
        }
    }
}
