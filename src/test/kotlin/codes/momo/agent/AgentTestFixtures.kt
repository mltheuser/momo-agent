package codes.momo.agent

import ai.router.sdk.AiRouterClient
import codes.momo.agent.harness.Harness
import java.net.InetAddress
import java.net.ServerSocket

/** The harness the agent-level unit tests run under. */
internal val TEST_HARNESS = Harness(
    model = "test-model",
    tools = listOf("bash", "read_file", "write_file", "edit_file"),
    instructions = "Unit-test instructions.",
)

/** A server that accepts connections but never answers, keeping LLM calls suspended. */
internal fun hangingServer(): ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

internal val ServerSocket.baseUrl: String
    get() = "http://127.0.0.1:$localPort"

/** A client for code paths that never reach the LLM: the bogus URL is never dialed. */
internal fun <T> withUnusedClient(block: (AiRouterClient) -> T): T =
    AiRouterClient("http://127.0.0.1:9").use(block)
