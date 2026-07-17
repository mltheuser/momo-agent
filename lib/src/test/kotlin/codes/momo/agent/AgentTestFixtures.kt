package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatResponse
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path

/** The harness the agent-level unit tests run under. */
internal val TEST_HARNESS = Harness(
    tools = listOf("bash", "read_file", "write_file", "edit_file"),
    instructions = "Unit-test instructions.",
)

/** Runs [block] against an agent on this workspace whose LLM serves [responses] in order. */
internal fun Path.withScriptedAgent(
    vararg responses: ChatResponse,
    harness: Harness = TEST_HARNESS,
    budgets: RunBudgets = RunBudgets(),
    listener: AgentEventListener = NoOpAgentEventListener,
    block: suspend CoroutineScope.(Agent) -> Unit,
) {
    scriptedServer(*responses).use { server ->
        AiRouterClient(server.baseUrl).use { client ->
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
    }
}

/** A server that accepts connections but never answers, keeping LLM calls suspended. */
internal fun hangingServer(): ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

/** A client for code paths that never reach the LLM: the bogus URL is never dialed. */
internal fun <T> withUnusedClient(block: (AiRouterClient) -> T): T =
    AiRouterClient("http://127.0.0.1:9").use(block)
