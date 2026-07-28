package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatMessage
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.SubagentType
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** [TEST_HARNESS] plus a self-referencing `self` subagent type, so children run the same harness. */
internal val SUBAGENT_HARNESS: Harness = selfReferencingHarness()

private fun selfReferencingHarness(): Harness {
    val self = SubagentType("An agent just like this one.")
    return Harness(
        tools = TEST_HARNESS.tools,
        instructions = TEST_HARNESS.instructions,
        subagents = mapOf("self" to self),
    ).also(self::resolveTo)
}

/** A harness whose subagent types resolve to already-built harnesses — the heterogeneous-fleet fixture. */
internal fun typedHarness(instructions: String, vararg types: Pair<String, Harness>): Harness = Harness(
    tools = TEST_HARNESS.tools,
    instructions = instructions,
    subagents = types.associate { (name, child) ->
        name to SubagentType("The $name harness.").also { it.resolveTo(child) }
    },
)

/** An agent on this workspace under [harness] ([SUBAGENT_HARNESS] by default). */
internal fun Path.agent(
    client: AiRouterClient,
    listener: AgentEventListener = NoOpAgentEventListener,
    budgets: RunBudgets = RunBudgets(),
    depth: Int = 0,
    harness: Harness = SUBAGENT_HARNESS,
): Agent = Agent(
    harness = harness,
    client = client,
    environment = LocalExecutionEnvironment(this),
    eventListener = listener,
    budgets = budgets,
    session = SessionState.Fresh("Test session", depth = depth),
)

/** Runs one prompt against a [FakeLlm] shared by the whole agent tree on this workspace. */
internal fun Path.runAgainstFake(
    listener: AgentEventListener,
    vararg rules: FakeLlmRule,
    budgets: RunBudgets = RunBudgets(),
    harness: Harness = SUBAGENT_HARNESS,
    settings: RunSettings = TEST_RUN_SETTINGS,
): RunResult =
    FakeLlm(*rules).client().use { client ->
        runBlocking { agent(client, listener, budgets, harness = harness).send(OPENING_PROMPT, settings) }
    }

/** The user message every [runAgainstFake] run opens with. */
internal const val OPENING_PROMPT: String = "go"

internal fun List<ChatMessage>.toolTexts(): List<String> = filter { it.role == "tool" }.map { it.text }
