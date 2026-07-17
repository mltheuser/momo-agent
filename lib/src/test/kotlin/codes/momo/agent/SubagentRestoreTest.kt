package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import codes.momo.agent.environment.LocalExecutionEnvironment
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SubagentRestoreTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Cancellation ─────────────────────────────────────────────────

    @Test
    @DisplayName("Cancelling the parent mid-child-run cascades to the child and both logs stay loadable")
    fun cancellationCascadesToTheChildAndBothLogsLoad() {
        val tree = TreeEventListener()
        val held = ScriptedReply.Held(assistantResponse(finishReason = "stop", text = "never delivered"))
        scriptedServer(
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "work away"),
            ).asReply(),
            held,
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                runBlocking {
                    val run = launch { workspace.agent(client, tree).send("go", TEST_RUN_SETTINGS) }
                    withTimeout(5.seconds) {
                        while (tree.children["helper"]?.events.orEmpty().none { it is AgentEvent.LlmCallStarted }) {
                            delay(10.milliseconds)
                        }
                    }

                    withTimeout(5.seconds) { run.cancelAndJoin() }

                    assertTrue(run.isCancelled)
                }
            }
        }
        // The held reply was never released, so only the cascade can have ended the child's run.
        val childEvents = assertNotNull(tree.children["helper"]).events
        assertTrue(childEvents.none { it is AgentEvent.RunFinished })

        // The parent's cut log loads with its dangling prompt call repaired.
        scriptedServer(assistantResponse(finishReason = "stop", text = "recovered")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = Agent.load(tree.events, SUBAGENT_HARNESS, client, LocalExecutionEnvironment(workspace))
                val result = runBlocking { parent.send("continue", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                val aborted = result.transcript.filter { it.role == "tool" }.last()
                assertEquals("call-2", aborted.toolCallId)
                assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)
            }
        }
        // The child's cut log loads too, keeping its identity.
        withUnusedClient { client ->
            val child = Agent.load(childEvents, SUBAGENT_HARNESS, client, LocalExecutionEnvironment(workspace))
            assertEquals(assertIs<AgentEvent.SessionStarted>(childEvents.first()).sessionId, child.sessionId)
        }
    }

    // ─── Restoring subagenthood ───────────────────────────────────────

    @Test
    @DisplayName("A restored child log keeps the subagent guidance in its system prompt")
    fun restoredChildKeepsSubagentGuidance() {
        val tree = TreeEventListener()
        workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )
        val childEvents = assertNotNull(tree.children["helper"]).events
        assertEquals(1, assertIs<AgentEvent.SessionStarted>(childEvents.first()).depth)

        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(requests, assistantResponse(finishReason = "stop", text = "still yours").asReply())
            .use { server ->
                AiRouterClient(server.baseUrl).use { client ->
                    val child = Agent.load(childEvents, SUBAGENT_HARNESS, client, LocalExecutionEnvironment(workspace))
                    val result = runBlocking { child.send("carry on", TEST_RUN_SETTINGS) }

                    assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                    assertContains(requests.single().messages.first().text, "spawned you")
                }
            }
    }

    @Test
    @DisplayName("Repeated save/restore at the depth cap keeps the subagent tools withheld")
    fun repeatedRestoreKeepsTheDepthCap() {
        val listener = CollectingEventListener()
        scriptedServer(assistantResponse(finishReason = "stop", text = "capped")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val capped = workspace.agent(client, listener, depth = Budgets.MAX_SUBAGENT_DEPTH)
                runBlocking { capped.send("go", TEST_RUN_SETTINGS) }
            }
        }
        var events = listener.events.toList()

        repeat(2) {
            val requests = CopyOnWriteArrayList<ChatRequest>()
            val continuation = CollectingEventListener()
            scriptedServer(requests, assistantResponse(finishReason = "stop", text = "still capped").asReply())
                .use { server ->
                    AiRouterClient(server.baseUrl).use { client ->
                        val restored = Agent.load(
                            events,
                            SUBAGENT_HARNESS,
                            client,
                            LocalExecutionEnvironment(workspace),
                            continuation,
                        )
                        runBlocking { restored.send("again", TEST_RUN_SETTINGS) }

                        assertEquals(TEST_HARNESS.tools, requests.single().tools.orEmpty().map { it.name })
                    }
                }
            events = events + continuation.events
        }
    }

    // ─── Reviving stored children ─────────────────────────────────────

    @Test
    @DisplayName("A restored parent revives a stored child by name and the child continues its conversation")
    fun restoredParentRevivesStoredChild() {
        val tree = TreeEventListener()
        workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "bake a cake"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "Which flavor should it be?").asReply(),
            assistantResponse(finishReason = "stop", text = "helper needs a flavor").asReply(),
        )
        val childEvents = assertNotNull(tree.children["helper"]).events
        val childId = assertIs<AgentEvent.SessionStarted>(childEvents.first()).sessionId

        val requests = CopyOnWriteArrayList<ChatRequest>()
        val restoredTree = object : TreeEventListener() {
            override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? =
                childEvents.takeIf { sessionId == childId }
        }
        scriptedServer(
            requests,
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "vanilla")).asReply(),
            assistantResponse(finishReason = "stop", text = "vanilla cake baked").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = Agent.load(
                    tree.events,
                    SUBAGENT_HARNESS,
                    client,
                    LocalExecutionEnvironment(workspace),
                    restoredTree,
                )
                val result = runBlocking { parent.send("make it vanilla", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals("vanilla cake baked", result.transcript.toolTexts().last())
                // The revived child continued its stored conversation on its LLM turn.
                val childRequest = requests[1]
                assertEquals(listOf("system", "user", "assistant", "user"), childRequest.messages.map { it.role })
                assertEquals("vanilla", childRequest.messages.last().text)
                // Revival re-attached observation through listenerForSubagent.
                val revived = assertNotNull(restoredTree.children["helper"])
                assertEquals(
                    "vanilla",
                    revived.events.filterIsInstance<AgentEvent.RunStarted>().single().userMessage,
                )
            }
        }
    }

    @Test
    @DisplayName("A dormant child whose stored log is gone resolves as unknown and frees its name")
    fun missingStoredChildFreesItsName() {
        val tree = TreeEventListener()
        workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )

        // The default storedEventsFor knows no sessions, as if the child's log were deleted.
        scriptedServer(
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "continue")).asReply(),
            toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")).asReply(),
            assistantResponse(finishReason = "stop", text = "respawned").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = Agent.load(tree.events, SUBAGENT_HARNESS, client, LocalExecutionEnvironment(workspace))
                val result = runBlocking { parent.send("check on the helper", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                val newToolTexts = result.transcript.toolTexts().takeLast(2)
                assertContains(newToolTexts[0], "Error: no subagent named 'helper'")
                assertContains(newToolTexts[1], "spawned subagent 'helper'")
            }
        }
    }

    @Test
    @DisplayName("A failing stored-log lookup surfaces as an error result and keeps the name registered")
    fun failingStoredChildLookupKeepsTheName() {
        val tree = TreeEventListener()
        workspace.runScripted(
            tree,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "the answer is 42").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        )

        // Corruption must not read as deletion: the failing lookup errors
        // the tool call, and the name stays taken.
        val failingLookup = object : TreeEventListener() {
            override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? =
                error("the stored log for $sessionId is unreadable")
        }
        scriptedServer(
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "continue")).asReply(),
            toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")).asReply(),
            assistantResponse(finishReason = "stop", text = "gave up").asReply(),
        ).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val parent = Agent.load(
                    tree.events,
                    SUBAGENT_HARNESS,
                    client,
                    LocalExecutionEnvironment(workspace),
                    failingLookup,
                )
                val result = runBlocking { parent.send("check on the helper", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                val newToolTexts = result.transcript.toolTexts().takeLast(2)
                assertContains(newToolTexts[0], "failed unexpectedly")
                assertContains(newToolTexts[1], "a subagent named 'helper' already exists")
            }
        }
    }
}
