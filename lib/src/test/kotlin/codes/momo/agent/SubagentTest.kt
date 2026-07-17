package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.notExists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SubagentTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Delegation round trips ───────────────────────────────────────

    @Test
    @DisplayName("Spawn + prompt in one assistant response returns the child's final message and logs the spawn")
    fun spawnAndPromptReturnTheChildsFinalMessage() {
        val tree = TreeEventListener()
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val result = runBlocking { workspace.agent(client, tree).send("go", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals("done", result.finalMessage)
                val toolTexts = result.transcript.toolTexts()
                assertContains(toolTexts[0], "spawned subagent 'helper'")
                assertEquals("the answer is 42", toolTexts[1])

                // The spawn is announced in the parent's log with the child's identity.
                val spawned = tree.events.filterIsInstance<AgentEvent.SubagentSpawned>().single()
                assertEquals("helper", spawned.name)
                val childEvents = assertNotNull(tree.children["helper"]).events
                val childStarted = assertIs<AgentEvent.SessionStarted>(childEvents.first())
                assertEquals(spawned.sessionId, childStarted.sessionId)
                assertEquals("helper", childStarted.title)
                assertEquals(
                    "compute the answer",
                    childEvents.filterIsInstance<AgentEvent.RunStarted>().single().userMessage,
                )

                // The child's LLM turn shows the subagent prompt and the child harness's toolset.
                val childRequest = requests[1]
                assertContains(childRequest.messages.first().text, "subagent")
                assertEquals(SUBAGENT_OFFERED_TOOLS, childRequest.tools.orEmpty().map { it.name })
            }
        }
    }

    @Test
    @DisplayName("A child's question round-trips: the parent answers with a second prompt and gets the completion")
    fun childQuestionAnsweredViaSecondPrompt() {
        val tree = TreeEventListener()

        val result = workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "bake a cake"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "Which flavor should it be?").asReply(),
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "vanilla")).asReply(),
            assistantResponse(finishReason = "stop", text = "vanilla cake baked").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertEquals("Which flavor should it be?", toolTexts[1])
        assertEquals("vanilla cake baked", toolTexts[2])
        // The child's two runs form one continuous conversation in one clean log.
        assertTwoCleanRuns(assertNotNull(tree.children["helper"]).events, secondUserMessage = "vanilla")
    }

    // ─── Run settings ─────────────────────────────────────────────────

    @Test
    @DisplayName("A parent run's settings are inherited by the subagent runs it drives")
    fun drivenChildRunsInheritTheParentRunsSettings() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val settings = RunSettings(model = "bigger-model", reasoningEffort = ReasoningEffort.HIGH)

                val result = runBlocking { workspace.agent(client).send("go", settings) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                // Parent turn, driven child turn, parent turn: all under the parent run's settings.
                assertEquals(3, requests.size)
                assertEquals(List(3) { "bigger-model" }, requests.map { it.model })
                assertEquals(List(3) { ReasoningEffort.HIGH }, requests.map { it.reasoningEffort })
            }
        }
    }

    @Test
    @DisplayName("A child prompted directly through its own send does not inherit the parent's earlier settings")
    fun directlyPromptedChildUsesOnlyItsOwnCallsSettings() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
            assistantResponse(finishReason = "stop", text = "direct answer").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = workspace.agent(client)
                val settings = RunSettings(model = "bigger-model", reasoningEffort = ReasoningEffort.HIGH)
                runBlocking {
                    assertEquals(RunResult.Status.COMPLETED, parent.send("go", settings).status)
                    val child = assertNotNull(parent.subagents["helper"])

                    val result = child.send("follow up", TEST_RUN_SETTINGS)

                    assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                    val direct = requests.last()
                    assertEquals(TEST_RUN_SETTINGS.model, direct.model)
                    assertNull(direct.reasoningEffort)
                }
            }
        }
    }

    // ─── Recursion ────────────────────────────────────────────────────

    @Test
    @DisplayName("A child spawns a grandchild and folds its answer into the parent's result")
    fun childSpawnsGrandchild() {
        val tree = TreeEventListener()

        val result = workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "child"),
                promptSubagentCall(id = "call-2", name = "child", message = "delegate this"),
            ).asReply(),
            toolCallResponse(
                spawnSubagentCall(id = "call-3", name = "grandchild"),
                promptSubagentCall(id = "call-4", name = "grandchild", message = "solve this"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "grandchild answer").asReply(),
            assistantResponse(finishReason = "stop", text = "child answer").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals("done", result.finalMessage)
        val child = assertNotNull(tree.children["child"])
        val grandchild = assertNotNull(child.children["grandchild"])
        assertEquals(
            "grandchild answer",
            child.events.filterIsInstance<AgentEvent.ToolCallFinished>().last().resultText,
        )
        assertEquals("grandchild answer", assertIs<AgentEvent.RunFinished>(grandchild.events.last()).finalMessage)
        assertEquals("child answer", assertIs<AgentEvent.RunFinished>(child.events.last()).finalMessage)
    }

    @Test
    @DisplayName("At the depth cap the subagent tools are not offered and a hallucinated call errors as unknown")
    fun subagentToolsWithheldAtDepthCap() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(spawnSubagentCall(id = "call-1", name = "too-deep")).asReply(),
            assistantResponse(finishReason = "stop", text = "understood").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val capped = workspace.agent(client, depth = Budgets.MAX_SUBAGENT_DEPTH)
                val result = runBlocking { capped.send("go", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals(TEST_HARNESS.tools, requests.first().tools.orEmpty().map { it.name })
                val toolText = result.transcript.toolTexts().single()
                assertContains(toolText, "unknown tool 'spawn_subagent'")
                assertContains(toolText, "available tools: bash, edit_file, read_file, write_file.")
            }
        }
    }

    @Test
    @DisplayName("One level below the cap the subagent tools are still offered")
    fun subagentToolsOfferedBelowDepthCap() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val nearCap = workspace.agent(client, depth = Budgets.MAX_SUBAGENT_DEPTH - 1)
                val result = runBlocking { nearCap.send("go", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals(SUBAGENT_OFFERED_TOOLS, requests.single().tools.orEmpty().map { it.name })
            }
        }
    }

    // ─── Typed spawning ───────────────────────────────────────────────

    @Test
    @DisplayName("Without a subagents map the subagent tools are never offered and a hallucinated call is unknown")
    fun noSubagentsMapMeansNoSubagentTools() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(spawnSubagentCall(id = "call-1", name = "helper")).asReply(),
            assistantResponse(finishReason = "stop", text = "understood").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val plain = workspace.agent(client, harness = TEST_HARNESS)
                val result = runBlocking { plain.send("go", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals(TEST_HARNESS.tools, requests.first().tools.orEmpty().map { it.name })
                val toolText = result.transcript.toolTexts().single()
                assertContains(toolText, "unknown tool 'spawn_subagent'")
                assertContains(toolText, "available tools: bash, edit_file, read_file, write_file.")
            }
        }
    }

    @Test
    @DisplayName("A spawned child runs the referenced harness of its type, one level deeper")
    fun spawnedChildRunsItsTypesHarness() {
        val child = Harness(tools = listOf("bash"), instructions = "Child harness instructions.")
        val parent = typedHarness("Parent harness instructions.", "worker" to child)
        val tree = TreeEventListener()
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", type = "worker"),
                promptSubagentCall(id = "call-2", name = "helper", message = "do the work"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "work done").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val result = runBlocking {
                    workspace.agent(client, tree, harness = parent).send("go", TEST_RUN_SETTINGS)
                }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                // The spawn tool's generated description enumerates the declared types.
                val spawnDefinition = requests[0].tools.orEmpty().single { it.name == "spawn_subagent" }
                assertContains(spawnDefinition.description.orEmpty(), "worker: The worker harness.")
                // The child's system prompt and toolset come from the child harness.
                val childRequest = requests[1]
                assertContains(childRequest.messages.first().text, "Child harness instructions.")
                assertContains(childRequest.messages.first().text, "spawned you")
                assertEquals(child.tools, childRequest.tools.orEmpty().map { it.name })
                // The spawn is recorded with its type, at one level deeper.
                val spawned = tree.events.filterIsInstance<AgentEvent.SubagentSpawned>().single()
                assertEquals("worker", spawned.type)
                assertNull(spawned.modelId)
                val childEvents = assertNotNull(tree.children["helper"]).events
                assertEquals(1, assertIs<AgentEvent.SessionStarted>(childEvents.first()).depth)
            }
        }
    }

    @Test
    @DisplayName("A spawn-time model_id pins the driven child's model while the effort stays inherited")
    fun spawnTimeModelIdPinsTheChildsModel() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", modelId = "pinned-model"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val tree = TreeEventListener()
                val settings = RunSettings(model = "parent-model", reasoningEffort = ReasoningEffort.HIGH)

                val result = runBlocking { workspace.agent(client, tree).send("go", settings) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                // Parent turn, driven child turn, parent turn: only the child's carries the pin.
                assertEquals(listOf("parent-model", "pinned-model", "parent-model"), requests.map { it.model })
                assertEquals(List(3) { ReasoningEffort.HIGH }, requests.map { it.reasoningEffort })
                assertEquals(
                    "pinned-model",
                    tree.events.filterIsInstance<AgentEvent.SubagentSpawned>().single().modelId,
                )
            }
        }
    }

    @Test
    @DisplayName("A directly prompted child uses its own call's model even when spawned with a pin")
    fun directlyPromptedChildIgnoresItsSpawnTimePin() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", modelId = "pinned-model"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
            assistantResponse(finishReason = "stop", text = "direct answer").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = workspace.agent(client)
                runBlocking {
                    assertEquals(RunResult.Status.COMPLETED, parent.send("go", TEST_RUN_SETTINGS).status)
                    val child = assertNotNull(parent.subagents["helper"])

                    val result = child.send("follow up", RunSettings(model = "direct-model"))

                    assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                    assertEquals("direct-model", requests.last().model)
                }
            }
        }
    }

    // ─── Error results ────────────────────────────────────────────────

    @Test
    @DisplayName("A blank model_id is an error result")
    fun blankModelIdIsAnErrorResult() {
        val result = workspace.runScripted(
            NoOpAgentEventListener,
            toolCallResponse(spawnSubagentCall(id = "call-1", name = "helper", modelId = "  ")).asReply(),
            assistantResponse(finishReason = "stop", text = "noted").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertContains(result.transcript.toolTexts().single(), "Error: model_id must not be blank when given.")
    }

    @Test
    @DisplayName("An unknown type is an error result listing the declared types")
    fun unknownTypeIsAnErrorResultListingDeclaredTypes() {
        val result = workspace.runScripted(
            NoOpAgentEventListener,
            toolCallResponse(spawnSubagentCall(id = "call-1", name = "helper", type = "ghost")).asReply(),
            assistantResponse(finishReason = "stop", text = "noted").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolText = result.transcript.toolTexts().single()
        assertContains(toolText, "Error: unknown subagent type 'ghost'")
        assertContains(toolText, "declared types: self.")
    }

    @Test
    @DisplayName("Duplicate and unknown names are error results, not failures")
    fun duplicateAndUnknownNamesAreErrorResults() {
        val result = workspace.runScripted(
            NoOpAgentEventListener,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                spawnSubagentCall(id = "call-2", name = "helper"),
                promptSubagentCall(id = "call-3", name = "ghost", message = "anyone there?"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "noted").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[0], "spawned subagent 'helper'")
        assertContains(toolTexts[1], "Error: a subagent named 'helper' already exists")
        assertContains(toolTexts[2], "Error: no subagent named 'ghost'")
        assertContains(toolTexts[2], "Existing subagents: helper.")
    }

    @Test
    @DisplayName("A child run ending non-COMPLETED surfaces as an error result naming the status")
    fun nonCompletedChildRunIsAnErrorResult() {
        val result = workspace.runScripted(
            NoOpAgentEventListener,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "try this"),
            ).asReply(),
            // The child's only turn reports a failed finish: its run ends as ERROR.
            assistantResponse(finishReason = "error").asReply(),
            assistantResponse(finishReason = "stop", text = "noted").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertContains(result.transcript.toolTexts()[1], "Error: subagent 'helper' run ended as ERROR")
    }

    @Test
    @DisplayName("A turns-exhausted child surfaces as an error result and a fresh prompt revives it")
    fun turnsExhaustedChildIsAnErrorResultAndRevivable() {
        val result = workspace.runScripted(
            NoOpAgentEventListener,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "grind away"),
            ).asReply(),
            // The child spends its whole inherited turn budget still wanting tools.
            toolCallResponse(bashCall(id = "call-c1", command = "true")).asReply(),
            toolCallResponse(bashCall(id = "call-c2", command = "true")).asReply(),
            toolCallResponse(bashCall(id = "call-c3", command = "true")).asReply(),
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "wrap up")).asReply(),
            assistantResponse(finishReason = "stop", text = "wrapped up").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
            budgets = RunBudgets(maxTurns = 3),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals("done", result.finalMessage)
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[1], "Error: subagent 'helper' run ended as TURNS_EXHAUSTED")
        // The next prompt continues the child under a fresh turn budget.
        assertEquals("wrapped up", toolTexts[2])
    }

    @Test
    @DisplayName("Prompting a child that is already running is an error result")
    fun busyChildIsAnErrorResult() {
        val marker = workspace.resolve("child-tool-started")
        scriptedServer(
            toolCallResponse(spawnSubagentCall(id = "call-1", name = "worker")).asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
            toolCallResponse(bashCall(id = "call-2", command = "touch '$marker' && sleep 30")).asReply(),
            toolCallResponse(promptSubagentCall(id = "call-3", name = "worker", message = "status?")).asReply(),
            assistantResponse(finishReason = "stop", text = "worker is busy").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = workspace.agent(client)
                runBlocking {
                    assertEquals(RunResult.Status.COMPLETED, parent.send("spawn a worker", TEST_RUN_SETTINGS).status)
                    // Occupy the child directly, the way the server API will
                    // allow; its slow tool call keeps its run active while
                    // freeing the scripted LLM for the parent's turns.
                    val child = assertNotNull(parent.subagents["worker"])
                    val busy = launch { child.send("keep busy", TEST_RUN_SETTINGS) }
                    withTimeout(5.seconds) {
                        while (marker.notExists()) {
                            delay(10.milliseconds)
                        }
                    }

                    val result = parent.send("check on the worker", TEST_RUN_SETTINGS)

                    assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                    assertContains(
                        result.transcript.toolTexts().last(),
                        "Error: subagent 'worker' is still working on an earlier prompt",
                    )

                    busy.cancelAndJoin()
                }
            }
        }
    }

    // ─── Budgets ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A child run outlasting the per-tool timeout succeeds, its wait excluded from the parent's clock")
    fun childRunIsTimeoutExemptAndExcludedFromParentClock() {
        val held = ScriptedReply.Held(assistantResponse(finishReason = "stop", text = "slow answer"))
        val listener = CollectingEventListener()
        scriptedServer(
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "take your time"),
            ).asReply(),
            held,
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = workspace.agent(client, listener, budgets = RunBudgets(toolTimeout = 100.milliseconds))
                runBlocking {
                    launch {
                        delay(2.seconds)
                        held.release()
                    }
                    val startMark = TimeSource.Monotonic.markNow()

                    val result = parent.send("go", TEST_RUN_SETTINGS)

                    val total = startMark.elapsedNow()
                    assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                    // Far past the 100 ms tool timeout, yet a success, not a timeout.
                    assertEquals("slow answer", result.transcript.toolTexts()[1])
                    assertTrue(total >= 2.seconds, "the run must have blocked on the child, took $total")
                    // The ~2 s block on the child is the child's time, not the parent's.
                    assertTrue(result.elapsed < 1.5.seconds, "expected the block excluded, elapsed ${result.elapsed}")
                    val lastBudget = listener.events.filterIsInstance<AgentEvent.BudgetUpdated>().last()
                    assertTrue(lastBudget.elapsed < 1.5.seconds, "budget elapsed was ${lastBudget.elapsed}")
                }
            }
        }
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

        val result = workspace.runScripted(
            listener,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolTexts = result.transcript.toolTexts()
        assertContains(toolTexts[0], "spawned subagent 'helper'")
        assertEquals("the answer is 42", toolTexts[1])
    }

    // ─── Subagent system prompt ───────────────────────────────────────

    @Test
    @DisplayName("A subagent's system prompt replaces the human-user guidance with the spawner contract")
    fun subagentSystemPromptStatesTheSpawnerContract() {
        val prompt = systemPromptFor(SUBAGENT_HARNESS, "/some/workspace", subagent = true)

        assertTrue(prompt.startsWith("Unit-test instructions."))
        assertContains(prompt, "/some/workspace")
        assertContains(prompt, "spawned you")
        assertContains(prompt, "final message")
        assertFalse(prompt.contains("hours or days"))
    }
}
