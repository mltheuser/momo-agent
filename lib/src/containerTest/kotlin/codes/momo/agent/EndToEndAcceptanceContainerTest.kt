package codes.momo.agent

import codes.momo.agent.environment.ContainerExecutionEnvironment
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * End-to-end acceptance in a container: the same toy task the local
 * variant runs, fully isolated, landing on the host only after `close()`.
 * It needs a Docker daemon on top of the live ai-router, which is why it
 * lives here instead of alongside the local variant.
 */
class EndToEndAcceptanceContainerTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Container acceptance: the toy task runs isolated, landing on the host only after close")
    fun containerToyTask() {
        val before = labeledContainers()
        ContainerExecutionEnvironment(image = "debian:12-slim", hostWorkspace = workspace).use { environment ->
            runToyTaskScenario(environment)
            assertToyTaskAbsent(workspace)
        }

        assertEquals(before, labeledContainers(), "close() must not leave this run's container behind")
        assertToyTaskWorkspace(workspace)
    }
}
