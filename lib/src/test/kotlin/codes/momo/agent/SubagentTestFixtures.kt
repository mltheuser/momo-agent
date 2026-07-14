package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatMessage
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** [TEST_HARNESS] with the subagent tools added. */
internal val SUBAGENT_HARNESS = TEST_HARNESS.copy(
    tools = TEST_HARNESS.tools + listOf("spawn_subagent", "prompt_subagent"),
)

/** An agent on this workspace under [SUBAGENT_HARNESS]. */
internal fun Path.agent(
    client: AiRouterClient,
    listener: AgentEventListener = NoOpAgentEventListener,
    budgets: RunBudgets = RunBudgets(),
    depth: Int = 0,
): Agent = Agent(
    harness = SUBAGENT_HARNESS,
    client = client,
    environment = LocalExecutionEnvironment(this),
    eventListener = listener,
    budgets = budgets,
    session = SessionState.Fresh("Test session", depth = depth),
)

/** Runs one "go" prompt against a scripted LLM shared by the whole agent tree on this workspace. */
internal fun Path.runScripted(
    listener: AgentEventListener,
    vararg replies: ScriptedReply,
    budgets: RunBudgets = RunBudgets(),
): RunResult =
    scriptedServer(*replies).use { server ->
        AiRouterClient(server.baseUrl).use { client ->
            runBlocking { agent(client, listener, budgets).send("go") }
        }
    }

internal fun List<ChatMessage>.toolTexts(): List<String> = filter { it.role == "tool" }.map { it.text }
