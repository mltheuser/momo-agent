package codes.momo.agent

import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** The harness the agent-level unit tests run under. */
internal val TEST_HARNESS = Harness(
    tools = listOf("bash"),
    instructions = "Unit-test instructions.",
)

/**
 * Runs [block] against an agent on this workspace whose LLM is a [FakeLlm]
 * over [rules], returning whatever the block does — for the cases that drive
 * the agent themselves rather than sending one prompt.
 */
internal fun <T> Path.withFakeAgent(
    vararg rules: FakeLlmRule,
    harness: Harness = TEST_HARNESS,
    budgets: RunBudgets = RunBudgets(),
    listener: AgentEventListener = NoOpAgentEventListener,
    block: suspend CoroutineScope.(Agent) -> T,
): T =
    FakeLlm(*rules).client().use { client ->
        val agent = Agent(
            harness = harness,
            client = client,
            environment = LocalExecutionEnvironment(this),
            eventListener = listener,
            budgets = budgets,
            session = SessionState.Fresh("Test session"),
        )
        runBlocking { block(agent) }
    }
