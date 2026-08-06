package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubagentRestoreTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    /** A restore listener whose stored-log lookup for dormant children is [storedEvents]. */
    private fun restoreListener(storedEvents: suspend (sessionId: String) -> List<AgentEvent>?): TreeEventListener =
        object : TreeEventListener() {
            override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = storedEvents(sessionId)
        }

    /** A restore listener serving [childEvents] as the stored log of the child session that recorded them. */
    private fun restoreListener(childEvents: List<AgentEvent>): TreeEventListener {
        val childId = assertIs<AgentEvent.SessionStarted>(childEvents.first()).sessionId
        return restoreListener { sessionId -> childEvents.takeIf { sessionId == childId } }
    }

    /**
     * One recorded run on [harness] in which the root spawns the child
     * `helper` of type [type], pinned to [PINNED_CHILD_MODEL] at
     * [PINNED_CHILD_EFFORT], and primes it; returns the tree's listener.
     */
    private fun recordSpawnOfHelper(
        harness: Harness = SUBAGENT_HARNESS,
        type: String = "self",
        childInstructions: String = harness.instructions,
    ): TreeEventListener {
        val tree = TreeEventListener()
        workspace.runAgainstFake(
            tree,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(
                        id = "call-1",
                        name = "helper",
                        type = type,
                        modelId = PINNED_CHILD_MODEL,
                        reasoningEffort = PINNED_CHILD_EFFORT,
                    ),
                    promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
                ),
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "ready"))
                .fromSubagent()
                .underInstructions(childInstructions)
                .forModel(PINNED_CHILD_MODEL)
                .atReasoningEffort(PINNED_CHILD_EFFORT),
            onToolResults(assistantResponse(finishReason = "stop", text = "spawned")),
            harness = harness,
            catalog = usableCatalog(PINNED_CHILD_MODEL),
        )
        return tree
    }

    /**
     * Records a stored child named `helper`, restores the parent under [restoredHarness] from
     * [editLog]'s view of the recorded log, and prompts the child again; asserts the revival
     * failure surfaced loudly and returns the failed prompt's tool text.
     */
    private fun assertRevivalFailsLoudly(
        restoredHarness: Harness,
        editLog: (List<AgentEvent>) -> List<AgentEvent> = { it },
    ): String {
        val tree = recordSpawnOfHelper()
        val childEvents = assertNotNull(tree.children["helper"]).events

        FakeLlm(
            onOpeningTurn(toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "continue"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "gave up")),
        ).client().use { client ->
            val parent = Agent.load(
                editLog(tree.events),
                restoredHarness,
                client,
                LocalExecutionEnvironment(workspace),
                restoreListener(childEvents),
            )
            val result = runBlocking { parent.send("check on the helper", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val toolText = result.transcript.toolTexts().last()
            assertContains(toolText, "failed unexpectedly")
            assertContains(toolText, SubagentRevivalException::class.simpleName!!)
            return toolText
        }
    }

    // ─── Reviving stored children ─────────────────────────────────────

    @Test
    @DisplayName("A restored parent revives its stored child, which answers under its own harness and spawn pins")
    fun restoredParentRevivesItsStoredChild() {
        val harness = typedHarness(
            "Unit-test instructions.",
            CHILD_TYPE to Harness(tools = listOf("bash"), instructions = ORACLE_INSTRUCTIONS),
        )
        val recorded = recordSpawnOfHelper(harness, CHILD_TYPE, childInstructions = ORACLE_INSTRUCTIONS)
        val childEvents = assertNotNull(recorded.children["helper"]).events
        val restored = restoreListener(childEvents)

        // Only the oracle-instructed child's own turn is given the answer, so
        // the parent can carry it back only by reviving that child and
        // prompting it under the harness its type declares and the model and
        // effort its spawn pinned — all restored from the stored log, none
        // the driving run's own.
        FakeLlm(
            onOpeningTurn(toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "well?")))
                .fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = ORACLE_ANSWER))
                .fromSubagent()
                .underInstructions(ORACLE_INSTRUCTIONS)
                .forModel(PINNED_CHILD_MODEL)
                .atReasoningEffort(PINNED_CHILD_EFFORT),
            onToolResults(assistantResponse(finishReason = "stop", text = "relayed")),
        ).client().use { client ->
            val parent = Agent.load(
                recorded.events,
                harness,
                client,
                LocalExecutionEnvironment(workspace),
                restored,
            )
            val result = runBlocking { parent.send("ask the helper", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            assertEquals(ORACLE_ANSWER, result.transcript.toolTexts().last(), "the child's answer is the tool result")
            assertTrue(
                restored.events.none { it is AgentEvent.SubagentSpawned },
                "a revival must reuse the stored child, never announce a new spawn",
            )
            // The revived child continued its stored log rather than starting
            // one: no SessionStarted, and its numbering picks up where the
            // stored log ended.
            val revived = assertNotNull(restored.children["helper"], "the revived child must register by name")
            val firstNewEvent = assertIs<AgentEvent.RunStarted>(revived.events.first())
            assertEquals(childEvents.last().sequenceId + 1, firstNewEvent.sequenceId)
            assertIs<AgentEvent.RunFinished>(revived.events.last(), "the revived child's own run must finish")
        }
    }

    @Test
    @DisplayName("Reviving a child whose type the harness no longer declares fails loudly, keeping the name")
    fun removedTypeFailsRevival() {
        // The parent's log restores into a harness that renamed the type away.
        val narrowed = typedHarness("Unit-test instructions.", "other" to TEST_HARNESS)

        val toolText = assertRevivalFailsLoudly(narrowed)

        assertContains(toolText, "which the harness no longer declares")
        assertContains(toolText, "Declared types: other.")
    }

    @Test
    @DisplayName("A stored spawn without a type — a log predating typed spawning — fails revival loudly")
    fun untypedStoredSpawnFailsRevival() {
        val toolText = assertRevivalFailsLoudly(SUBAGENT_HARNESS) { log ->
            log.map { event ->
                if (event is AgentEvent.SubagentSpawned) {
                    event.copy(type = null, modelId = null, reasoningEffort = null)
                } else {
                    event
                }
            }
        }

        assertContains(toolText, "predating typed spawning")
    }

    @Test
    @DisplayName("A dormant child whose stored log is gone resolves as unknown and frees its name")
    fun missingStoredChildFreesItsName() {
        val tree = recordSpawnOfHelper()

        // The default storedEventsFor knows no sessions, as if the child's log were deleted.
        FakeLlm(
            onOpeningTurn(toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "continue"))),
            onToolResults(
                toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")),
                saying = "no subagent named 'helper'",
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "respawned"), saying = "spawned subagent"),
        ).client().use { client ->
            val parent = Agent.load(tree.events, SUBAGENT_HARNESS, client, LocalExecutionEnvironment(workspace))
            val result = runBlocking { parent.send("check on the helper", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val newToolTexts = result.transcript.toolTexts().takeLast(2)
            assertContains(newToolTexts[0], "Error: no subagent named 'helper'")
            assertContains(newToolTexts[1], "spawned subagent 'helper'")
        }
    }

    @Test
    @DisplayName("A failing stored-log lookup surfaces as an error result and keeps the name registered")
    fun failingStoredChildLookupKeepsTheName() {
        val tree = recordSpawnOfHelper()

        // Corruption must not read as deletion: the failing lookup errors
        // the tool call, and the name stays taken.
        val failingLookup = restoreListener { sessionId -> error("the stored log for $sessionId is unreadable") }
        FakeLlm(
            onOpeningTurn(toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "continue"))),
            onToolResults(
                toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")),
                saying = "failed unexpectedly",
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "gave up"), saying = "already exists"),
        ).client().use { client ->
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

/** The declared subagent type whose harness is the only place [ORACLE_ANSWER] is served. */
private const val CHILD_TYPE: String = "oracle"

/** The spawn-time model pin every recorded child carries, and the driving run's is not. */
private const val PINNED_CHILD_MODEL: String = "pinned-child-model:cloud@fake-provider"

/** The spawn-time effort pin beside it; the driving runs carry none. */
private val PINNED_CHILD_EFFORT: ReasoningEffort = ReasoningEffort.LOW

private const val ORACLE_INSTRUCTIONS: String = "Oracle-harness instructions."

private const val ORACLE_ANSWER: String = "the oracle has spoken"
