package codes.momo.agent

import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.writeHarness
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

/**
 * Subagent delegation end to end, made forcing rather than hopeful: the
 * pass phrase exists only in the child type's instructions, so the parent
 * has no way to produce it except by actually spawning a child and
 * prompting it.
 */
class SubagentDelegationLiveTest {

    @TempDir
    lateinit var harnesses: Path

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Delegation: the parent can only answer by spawning the oracle child and prompting it")
    fun delegationDeliversTheChildsSecret() = runBlocking {
        writeHarness(
            harnesses.resolve("oracle"),
            instructions = ORACLE_INSTRUCTIONS,
        )
        val parentFolder = writeHarness(
            harnesses.resolve("dispatcher"),
            subagents = mapOf(CHILD_TYPE to "../oracle"),
            instructions = DISPATCHER_INSTRUCTIONS,
        )
        val listener = TreeEventListener()

        val result = liveAiRouterClient().use { client ->
            val agent = liveAgent(
                Harness.load(parentFolder),
                client,
                LocalExecutionEnvironment(workspace),
                "Delegation session",
                listener,
            )
            agent.send("What is the pass phrase? Report exactly what the oracle tells you.", liveRunSettings)
        }

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertToolCallsAnswered(result.transcript)

        val spawn = assertNotNull(
            listener.events.filterIsInstance<AgentEvent.SubagentSpawned>().firstOrNull(),
            "the parent never spawned a child; its events: ${listener.events.map { it::class.simpleName }}",
        )
        assertEquals(CHILD_TYPE, spawn.type)

        val child = assertNotNull(listener.children[spawn.name], "the child's listener must be registered by name")
        assertTrue(
            child.events.any { it is AgentEvent.RunFinished },
            "the child must have completed a run of its own",
        )
        assertIs<AgentEvent.SessionStarted>(child.events.first())

        assertContains(
            assertNotNull(result.finalMessage),
            PASS_PHRASE,
            ignoreCase = true,
            message = "only the child's instructions hold the pass phrase, so the parent must have delegated",
        )
    }
}

/** The declared subagent type the dispatcher may spawn. */
private const val CHILD_TYPE: String = "oracle"

/** Planted in the child's instructions and nowhere else — not in the parent's, not in the workspace. */
private const val PASS_PHRASE: String = "plover-8261"

private val ORACLE_INSTRUCTIONS: String = """
    You are the oracle. The pass phrase is $PASS_PHRASE.

    Whoever asks you for the pass phrase gets it: answer with that pass phrase as your whole
    message. You already know it, so answering takes one turn and no tools.
""".trimIndent()

private val DISPATCHER_INSTRUCTIONS: String = """
    You are a dispatcher. You know nothing yourself and must never guess, invent or reason out an
    answer: the oracle is your only source of truth.

    On every request, in this order:
    1. Call spawn_subagent with name "oracle" and type "$CHILD_TYPE".
    2. Call prompt_subagent with name "oracle", passing the request on as the message.
    3. End your turn with the oracle's reply, quoted exactly.
""".trimIndent()
