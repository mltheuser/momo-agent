package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SubagentTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    /**
     * Drives a child under [budgets] over [childRules] to whatever end those
     * two produce, and returns the parent's tool result for the prompt that
     * waited on it — the only thing the parent ever learns about the run.
     */
    private fun childOutcomeAsToolResult(budgets: RunBudgets, vararg childRules: FakeLlmRule): String {
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent(),
            *childRules,
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")).fromRootAgent(),
            budgets = budgets,
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        return result.transcript.toolTexts()[1]
    }

    // ─── Depth cap ────────────────────────────────────────────────────

    @Test
    @DisplayName("At the depth cap the subagent tools are not offered and a hallucinated call errors as unknown")
    fun subagentToolsWithheldAtDepthCap() {
        FakeLlm(
            onOpeningTurn(toolCallResponse(spawnSubagentCall(id = "call-1", name = "too-deep"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "understood")),
        ).client().use { client ->
            val capped = workspace.agent(client, depth = Budgets.MAX_SUBAGENT_DEPTH)
            val result = runBlocking { capped.send("go", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val toolText = result.transcript.toolTexts().single()
            assertContains(toolText, "unknown tool 'spawn_subagent'")
            assertContains(toolText, "available tools: bash.")
        }
    }

    // ─── Run settings across the boundary ─────────────────────────────

    @Test
    @DisplayName("A parent run's settings are inherited by the subagent runs it drives")
    fun drivenChildRunsInheritTheParentRunsSettings() {
        // Every rule is narrowed to the parent run's settings, so a turn —
        // the parent's or the child it drives — going out on anything else
        // finds no reply and cannot be mistaken for a pass.
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent().forModel(BIGGER_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER))
                .fromSubagent().forModel(BIGGER_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent().forModel(BIGGER_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            settings = RunSettings(model = BIGGER_MODEL, reasoningEffort = ReasoningEffort.HIGH),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals(CHILD_ANSWER, result.transcript.toolTexts()[1], "the child answered under the parent's settings")
    }

    // ─── Typed spawning ───────────────────────────────────────────────

    @Test
    @DisplayName("A spawn-time model_id pins the driven child's model; the unpinned effort inherits the run's")
    fun spawnTimeModelIdPinsTheChildsModel() {
        val tree = TreeEventListener()

        val result = workspace.runAgainstFake(
            tree,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", modelId = PINNED_MODEL),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent().forModel(PARENT_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            // Only the child's turn goes to the pinned model; its effort is
            // still the driving run's.
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER))
                .fromSubagent().forModel(PINNED_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent().forModel(PARENT_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            settings = RunSettings(model = PARENT_MODEL, reasoningEffort = ReasoningEffort.HIGH),
            catalog = usableCatalog(PINNED_MODEL),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals(CHILD_ANSWER, result.transcript.toolTexts()[1], "the child answered on the pinned model")
        assertEquals(PINNED_MODEL, tree.events.filterIsInstance<AgentEvent.SubagentSpawned>().single().modelId)
    }

    @Test
    @DisplayName("A spawn-time reasoning_effort pins the driven child's effort; the unpinned model inherits the run's")
    fun spawnTimeReasoningEffortPinsTheChildsEffort() {
        val tree = TreeEventListener()

        val result = workspace.runAgainstFake(
            tree,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", reasoningEffort = ReasoningEffort.LOW),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent().forModel(PARENT_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            // Only the child's turn runs at the pinned effort; its model is
            // still the driving run's.
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER))
                .fromSubagent().forModel(PARENT_MODEL).atReasoningEffort(ReasoningEffort.LOW),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent().forModel(PARENT_MODEL).atReasoningEffort(ReasoningEffort.HIGH),
            settings = RunSettings(model = PARENT_MODEL, reasoningEffort = ReasoningEffort.HIGH),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals(CHILD_ANSWER, result.transcript.toolTexts()[1], "the child answered at the pinned effort")
        val spawned = tree.events.filterIsInstance<AgentEvent.SubagentSpawned>().single()
        assertEquals(ReasoningEffort.LOW, spawned.reasoningEffort)
        assertEquals(null, spawned.modelId)
    }

    @Test
    @DisplayName("A directly prompted child uses its own call's model even when spawned with a pin")
    fun directlyPromptedChildIgnoresItsSpawnTimePin() {
        FakeLlm(
            usableCatalog(PINNED_MODEL),
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", modelId = PINNED_MODEL),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent().forModel(PARENT_MODEL),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER))
                .fromSubagent().forModel(PINNED_MODEL),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent().forModel(PARENT_MODEL),
            // The child's own send: only that call's model, never the pin.
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "direct answer"), saying = "follow up")
                .fromSubagent().forModel(DIRECT_MODEL),
        ).client().use { client ->
            val parent = workspace.agent(client)
            runBlocking {
                assertEquals(RunResult.Status.COMPLETED, parent.send("go", RunSettings(PARENT_MODEL)).status)
                val child = assertNotNull(parent.subagents["helper"])

                val result = child.send("follow up", RunSettings(DIRECT_MODEL))

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals("direct answer", result.finalMessage)
            }
        }
    }

    // ─── Budgets across the boundary ──────────────────────────────────

    @Test
    @DisplayName("A child run outlasting the per-tool timeout still succeeds, and its time is off the parent's clock")
    fun childRunIsTimeoutExemptAndExcludedFromParentClock() {
        val tree = TreeEventListener()

        val result = workspace.runAgainstFake(
            tree,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "take your time"),
                ),
            ).fromRootAgent(),
            // The child spends real time in bash: every sleep is cut at the
            // per-tool timeout, and three of them outlast it several times
            // over — no pausable LLM needed, just a slow tool.
            onOpeningTurn(
                toolCallResponse(
                    bashCall(id = "child-1", command = "sleep 30"),
                    bashCall(id = "child-2", command = "sleep 30"),
                    bashCall(id = "child-3", command = "sleep 30"),
                ),
            ).fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = CHILD_ANSWER)).fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed")).fromRootAgent(),
            budgets = RunBudgets(toolTimeout = SLOW_TOOL_TIMEOUT),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val childCall = tree.events.filterIsInstance<AgentEvent.ToolCallFinished>().single { it.callId == "call-2" }
        assertEquals(
            AgentEvent.ToolCallFinished.Outcome.SUCCESS,
            childCall.outcome,
            "waiting on a child is exempt from the per-tool timeout",
        )
        assertEquals(CHILD_ANSWER, childCall.resultText, "the child's final message is the parent's tool result")
        assertTrue(
            childCall.duration > SLOW_TOOL_TIMEOUT,
            "the child must outlast the timeout for the exemption to mean anything, took ${childCall.duration}",
        )
        assertTrue(
            result.elapsed < childCall.duration,
            "blocked time is the child's to account for: elapsed ${result.elapsed} vs ${childCall.duration}",
        )
    }

    // ─── A child's outcome as the parent's tool result ────────────────

    @Test
    @DisplayName("A child run ending as ERROR reaches the parent as an error result naming that status")
    fun childErrorReachesTheParentAsAnErrorResult() {
        val toolText = childOutcomeAsToolResult(
            RunBudgets(),
            onOpeningTurn(assistantResponse(finishReason = "error")).fromSubagent(),
        )

        assertContains(toolText, "Error: subagent 'helper' run ended as ERROR")
        assertContains(
            toolText,
            "received this prompt",
            message = "the parent must learn the prompt was received",
        )
    }

    @Test
    @DisplayName("A child run ending as TURNS_EXHAUSTED reaches the parent as an error result naming that status")
    fun childTurnExhaustionReachesTheParentAsAnErrorResult() {
        // Two turns is exactly what the parent needs — call the tools, then
        // answer — while the child asks for a tool on both of its own.
        val toolText = childOutcomeAsToolResult(
            RunBudgets(maxTurns = 2),
            onOpeningTurn(toolCallResponse(bashCall(id = "child-1", command = "echo one"))).fromSubagent(),
            onToolResults(toolCallResponse(bashCall(id = "child-2", command = "echo two"))).fromSubagent(),
        )

        assertContains(toolText, "Error: subagent 'helper' run ended as TURNS_EXHAUSTED")
    }

    @Test
    @DisplayName("A child run ending as TIMEOUT reaches the parent as an error result naming that status")
    fun childTimeoutReachesTheParentAsAnErrorResult() {
        // The child spends the whole budget in one real sleep. The parent
        // shares the budget but not the clock: its time blocked on the child
        // is the child's to account for, so its own two turns stay affordable.
        val toolText = childOutcomeAsToolResult(
            RunBudgets(maxWallClock = SHARED_WALL_CLOCK),
            onOpeningTurn(toolCallResponse(bashCall(id = "child-1", command = "sleep 30"))).fromSubagent(),
        )

        assertContains(toolText, "Error: subagent 'helper' run ended as TIMEOUT")
    }

    @Test
    @DisplayName("A stop cutting the parent mid-prompt tells it, per prompt call, whether the child received it")
    fun stopMidPromptRepairsReceiptIntoTheParentsTranscript() {
        val childWorking = CompletableDeferred<Unit>()
        val listener = object : AgentEventListener {
            override fun onEvent(event: AgentEvent) = Unit

            override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
                AgentEventListener { event ->
                    if (event is AgentEvent.ToolCallStarted) childWorking.complete(Unit)
                }
        }
        FakeLlm(
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "first errand"),
                    promptSubagentCall(id = "call-3", name = "helper", message = "second errand"),
                ),
            ).fromRootAgent(),
            // Real time in a tool holds the child mid-prompt until the stop
            // lands; the second prompt call is still queued behind it.
            onOpeningTurn(toolCallResponse(bashCall(id = "child-1", command = "sleep 30"))).fromSubagent(),
        ).client().use { client ->
            val parent = workspace.agent(client, listener)
            runBlocking {
                val run = async(Dispatchers.IO) { parent.send("go", TEST_RUN_SETTINGS) }
                withTimeout(5.seconds) { childWorking.await() }

                parent.stop()

                val result = withTimeout(5.seconds) { run.await() }
                assertEquals(RunResult.Status.STOPPED, result.status)
                val toolTexts = result.transcript.toolTexts()
                assertContains(toolTexts[0], "spawned subagent 'helper'")
                // The blocked prompt reads the cascade-stopped child's own
                // outcome; the queued one never dispatched and says so.
                assertContains(toolTexts[1], "run ended as STOPPED")
                assertContains(toolTexts[1], "received this prompt")
                assertContains(toolTexts[2], "a user stopped the run before this call could execute")
                assertContains(toolTexts[2], "never received this message")
            }
        }
    }

    // ─── Error results ────────────────────────────────────────────────

    @Test
    @DisplayName("An unknown type is an error result listing the declared types")
    fun unknownTypeIsAnErrorResultListingDeclaredTypes() {
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(toolCallResponse(spawnSubagentCall(id = "call-1", name = "helper", type = "ghost"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolText = result.transcript.toolTexts().single()
        assertContains(toolText, "Error: unknown subagent type 'ghost'")
        assertContains(toolText, "declared types: self.")
    }

    @Test
    @DisplayName("Duplicate and unknown names are error results, not failures")
    fun duplicateAndUnknownNamesAreErrorResults() {
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    spawnSubagentCall(id = "call-2", name = "helper"),
                    promptSubagentCall(id = "call-3", name = "ghost", message = "anyone there?"),
                ),
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[0], "spawned subagent 'helper'")
        assertContains(toolTexts[1], "Error: a subagent named 'helper' already exists")
        assertContains(toolTexts[2], "Error: no subagent named 'ghost'")
        assertContains(toolTexts[2], "Existing subagents: helper.")
    }

    @Test
    @DisplayName("A model_id without its @provider suffix still addresses its catalog entry")
    fun modelIdWithoutProviderSuffixIsAccepted() {
        val shortForm = PINNED_MODEL.substringBeforeLast('@')

        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", modelId = shortForm),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER))
                .fromSubagent().forModel(shortForm),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent(),
            catalog = usableCatalog(PINNED_MODEL),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals(CHILD_ANSWER, result.transcript.toolTexts()[1], "the child answered on the short-form pin")
    }

    @Test
    @DisplayName("An unknown model_id is an error result listing the closest usable models, and frees the name")
    fun unknownModelIdIsAnErrorResultListingClosestModels() {
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", modelId = "opus 5"),
                    spawnSubagentCall(id = "call-2", name = "helper"),
                ),
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")),
            catalog = usableCatalog(
                "anthropic/claude-opus-5:cloud@openrouter",
                "qwen3.5:27b:local@ollama",
            ),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[0], "Error: model_id 'opus 5' is not in the router's catalog")
        // Closest first, and the whole usable catalog fits under the cap.
        assertContains(toolTexts[0], "- anthropic/claude-opus-5:cloud@openrouter\n- qwen3.5:27b:local@ollama")
        assertContains(toolTexts[0], "Pass one of these verbatim")
        assertContains(toolTexts[1], "spawned subagent 'helper'", message = "a rejected spawn must not take the name")
    }

    @Test
    @DisplayName("Near-miss model_id forms — wrong provider, wrong case, missing tag — are all rejected")
    fun nearMissModelIdFormsAreRejected() {
        // Each is one edit away from addressing PINNED_MODEL, and each is a
        // form the router would refuse: the match is exactly what it resolves,
        // never a lenient reading.
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "a", modelId = "pinned-model:cloud@wrong-provider"),
                    spawnSubagentCall(id = "call-2", name = "b", modelId = "Pinned-Model:cloud@fake-provider"),
                    spawnSubagentCall(id = "call-3", name = "c", modelId = "pinned-model"),
                ),
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")),
            catalog = usableCatalog(PINNED_MODEL),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        for (toolText in result.transcript.toolTexts()) {
            assertContains(toolText, "is not in the router's catalog")
            assertContains(toolText, "- $PINNED_MODEL", message = "the rejection must still suggest the entry")
        }
    }

    @Test
    @DisplayName("An unreadable catalog is an error result naming the failure, never a silently accepted pin")
    fun unreachableCatalogIsAnErrorResult() {
        // No catalog handed to the fake: the listing draws its failing status.
        val result = workspace.runAgainstFake(
            NoOpAgentEventListener,
            onOpeningTurn(toolCallResponse(spawnSubagentCall(id = "call-1", name = "helper", modelId = "some-model"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "noted")),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolText = result.transcript.toolTexts().single()
        assertContains(toolText, "Error: model_id 'some-model' could not be validated against the router's catalog")
    }

    // ─── Listener robustness ──────────────────────────────────────────

    @Test
    @DisplayName("A throwing listenerForSubagent degrades to no observation, never a failed spawn")
    fun throwingSubagentListenerDoesNotFailTheSpawn() {
        val listener = object : AgentEventListener {
            override fun onEvent(event: AgentEvent) = Unit

            override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
                error("cannot observe the child")
        }

        val result = workspace.runAgainstFake(
            listener,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
                ),
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER)).fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed"), saying = CHILD_ANSWER)
                .fromRootAgent(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[0], "spawned subagent 'helper'")
        assertEquals(CHILD_ANSWER, toolTexts[1], "the unobserved child still ran and answered")
    }

    // ─── Subagent system prompt ───────────────────────────────────────

    @Test
    @DisplayName("A subagent's system prompt replaces the human-user guidance with the spawner contract")
    fun subagentSystemPromptStatesTheSpawnerContract() {
        val prompt = systemPromptFor(SUBAGENT_HARNESS, subagent = true)

        assertTrue(prompt.startsWith("Unit-test instructions."))
        assertContains(prompt, "spawned you")
        assertContains(prompt, "final message")
        assertFalse(prompt.contains("hours or days"))
    }
}

/** Small enough that a few real sleeps dwarf it, large enough that starting them cannot breach it. */
private val SLOW_TOOL_TIMEOUT: Duration = 300.milliseconds

/** The child's sleep dwarfs it; the parent's own turns, all it is measured against, cannot approach it. */
private val SHARED_WALL_CLOCK: Duration = 2.seconds

/** Served only to the child, so the parent's tool result can only have come from it. */
private const val CHILD_ANSWER: String = "the child is finally done"

/** The model a run whose settings the whole tree inherits goes to. */
private const val BIGGER_MODEL: String = "bigger-model"

/** The models of the three ways a child's model can be decided: the driving run's, the spawn's, its own call's. */
private const val PARENT_MODEL: String = "parent-model"
private const val PINNED_MODEL: String = "pinned-model:cloud@fake-provider"
private const val DIRECT_MODEL: String = "direct-model"
