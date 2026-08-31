package codes.momo.agent

import codes.momo.agent.environment.ExecutionEnvironment
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * End-to-end acceptance: the example coder harness completes the toy task
 * through the full public surface — harness loading, the agent loop, core
 * tools, a question-and-answer round over two prompts, the event stream.
 */
class EndToEndAcceptanceLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Local acceptance: the coder harness completes the toy task in a local workspace")
    fun localToyTask() {
        runToyTaskScenario(ExecutionEnvironment(workspace))

        assertToyTaskWorkspace(workspace)
    }
}
