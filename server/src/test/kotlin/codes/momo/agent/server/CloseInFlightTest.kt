package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.AgentInput
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloseInFlightTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Closing a session with a prompt in flight aborts the send, then tears down and stays resumable")
    fun closeAbortsAnInFlightSend() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = EnvironmentSpec.Local(tempDir.resolve("workspace").createDirectories().toString())

        scriptedServer().use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                SessionRegistry(dataDir, client).use { registry ->
                    runBlocking {
                        val id = registry.create(harness, workspace).id
                        val runtime = registry.attach(id)
                        val send = async { runtime.send(AgentInput.UserMessage("hang forever")) }
                        withTimeout(WAIT_MILLIS) {
                            while (!runtime.sendInFlight) {
                                yield()
                            }
                        }
                        assertEquals(SessionStatus.RUNNING, registry.info(id).status)

                        registry.close(id)

                        assertFailsWith<CancellationException> { send.await() }
                        assertEquals(SessionStatus.CLOSED, registry.info(id).status)

                        // The aborted run's log reloads into a usable session.
                        registry.attach(id)
                        assertEquals(SessionStatus.IDLE, registry.info(id).status)
                    }
                }
            }
        }
    }
}

private const val WAIT_MILLIS = 10_000L
