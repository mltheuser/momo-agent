package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.server.session.SessionRegistry
import codes.momo.agent.server.session.shutdown
import codes.momo.agent.server.storage.TemplateStore
import codes.momo.agent.server.storage.repairTornRuns
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

public fun main(args: Array<String>) {
    val config = ServerConfig.resolve(args.toList(), System.getenv())
    val client = AiRouterClient(config.aiRouterBaseUrl)
    val registry = SessionRegistry(config.dataDir, client)
    registry.store.repairTornRuns()
    val templates = TemplateStore(config.dataDir)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            registry.shutdown()
            client.close()
        },
    )
    embeddedServer(CIO, host = "127.0.0.1", port = config.port) { agentServer(registry, client, templates) }
        .start(wait = true)
}
