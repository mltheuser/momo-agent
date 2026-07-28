package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.assistantResponse
import codes.momo.agent.atReasoningEffort
import codes.momo.agent.forModel
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A prompt's per-run model settings, from its request body to the outgoing
 * LLM call. The live tier cannot see this — any model answers there — and the
 * fake is what makes it visible: its one rule answers only the model and
 * effort the prompt asked for, so a run that reaches the LLM on anything else
 * finds no reply and ends in error. `run_started` records what a replaying
 * client reads back.
 */
class PromptModelTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A prompt's model and effort reach the LLM call and are recorded on run_started")
    fun modelAndEffortReachTheLlmCallAndTheLog() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done"))
                .forModel(BIGGER_MODEL)
                .atReasoningEffort(ReasoningEffort.HIGH),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            http.prompt(id, "go", model = BIGGER_MODEL, reasoningEffort = ReasoningEffort.HIGH)
            val events = http.streamEvents(id).map { it.event }

            assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(events.last()).status)
            val started = events.filterIsInstance<AgentEvent.RunStarted>().single()
            assertEquals(BIGGER_MODEL, started.model)
            assertEquals(ReasoningEffort.HIGH, started.reasoningEffort)
        }
    }

    @Test
    @DisplayName("Effort stays optional: a prompt without one runs its model asking for no effort at all")
    fun effortlessPromptRunsWithNoEffort() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done"))
                .forModel(TEST_RUN_SETTINGS.model)
                .atReasoningEffort(null),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            http.prompt(id, "go")
            val events = http.streamEvents(id).map { it.event }

            assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(events.last()).status)
            val started = events.filterIsInstance<AgentEvent.RunStarted>().single()
            assertEquals(TEST_RUN_SETTINGS.model, started.model)
            assertNull(started.reasoningEffort)
        }
    }
}

/** Any model but the suite's default, so the run cannot be reaching the LLM on that. */
private const val BIGGER_MODEL: String = "bigger-model"
