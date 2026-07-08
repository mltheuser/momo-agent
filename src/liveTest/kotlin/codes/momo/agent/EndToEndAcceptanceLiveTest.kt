package codes.momo.agent

import codes.momo.agent.environment.ContainerExecutionEnvironment
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.environment.runProcess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end acceptance: the example coder harness completes the toy task
 * through the full public surface — harness loading, the agent loop, core
 * tools, an ask_user pause/resume round, the event stream — in a local
 * workspace and fully isolated in a container.
 */
class EndToEndAcceptanceLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Local acceptance: the coder harness completes the toy task in a local workspace")
    fun localToyTask() {
        runToyTaskScenario(LocalExecutionEnvironment(workspace))

        assertToyTaskWorkspace(workspace)
    }

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

    /** Names of momo-agent containers currently known to docker, found via the cleanup label. */
    private fun labeledContainers(): Set<String> = runBlocking {
        val listing = runProcess(
            listOf("docker", "ps", "-a", "--filter", "label=codes.momo.agent", "--format", "{{.Names}}"),
            timeout = 30.seconds,
        )
        val completed = assertIs<ExecResult.Completed>(listing, "docker ps timed out")
        assertEquals(0, completed.exitCode, "docker ps failed: ${completed.stderr}")
        completed.stdout.lines().filter { it.isNotBlank() }.toSet()
    }
}
