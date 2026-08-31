package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Runs the agent server: the localhost-only HTTP front door over the
 * library's agent sessions. Configuration per [ServerConfig].
 */
public fun main(args: Array<String>) {
    val config = ServerConfig.resolve(args.toList(), System.getenv())
    val client = AiRouterClient(config.aiRouterBaseUrl)
    val registry = SessionRegistry(config.dataDir, client)
    val templates = TemplateStore(config.dataDir)
    // Close every live session before the process dies; their stored logs
    // make them resumable.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            registry.close()
            client.close()
        },
    )
    embeddedServer(CIO, host = "127.0.0.1", port = config.port) { agentServer(registry, client, templates) }
        .start(wait = true)
}
