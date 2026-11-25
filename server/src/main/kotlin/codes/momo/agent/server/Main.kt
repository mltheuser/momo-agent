package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.server.change.ChangeSignal
import codes.momo.agent.server.script.ScriptRegistry
import codes.momo.agent.server.session.SessionRegistry
import codes.momo.agent.server.session.shutdown
import codes.momo.agent.server.storage.SessionStore
import codes.momo.agent.server.storage.TemplateStore
import codes.momo.agent.server.storage.deleteExpiredTrees
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

public fun main(args: Array<String>) {
    val config = ServerConfig.resolve(args.toList(), System.getenv())
    val client = AiRouterClient(config.aiRouterBaseUrl)
    val changes = ChangeSignal()
    val sessions = SessionStore(config.dataDir)
    sessions.deleteExpiredTrees()
    val registry = SessionRegistry(sessions, client, changes)
    val scripts = ScriptRegistry(config.dataDir, "http://127.0.0.1:${config.port}", changes)
    val templates = TemplateStore(config.dataDir)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            registry.shutdown()
            client.close()
        },
    )
    embeddedServer(CIO, host = "127.0.0.1", port = config.port) {
        agentServer(registry, scripts, changes, client, templates)
    }.start(wait = true)
}
