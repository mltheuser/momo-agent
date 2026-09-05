package codes.momo.agent.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import java.nio.file.Path

internal fun normalizedWorkspace(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

internal fun ApplicationCall.workspaceParameter(): String? {
    val raw = request.queryParameters[WORKSPACE_PARAMETER] ?: return null
    if (raw.isBlank()) {
        throw BadRequestException("A workspace must not be blank.")
    }
    if (!Path.of(raw).isAbsolute) {
        throw BadRequestException("A workspace must be an absolute path, not: $raw")
    }
    return raw
}

internal fun ApplicationCall.requiredWorkspace(): String =
    workspaceParameter() ?: throw BadRequestException(
        "A session listing is scoped to one workspace: name it with a ?$WORKSPACE_PARAMETER=<absolute path>.",
    )

internal fun Route.scopeToWorkspace(registry: SessionRegistry) {
    install(
        createRouteScopedPlugin("WorkspaceScopeGuard") {
            onCall { call ->
                val workspace = call.workspaceParameter() ?: return@onCall
                registry.requireInWorkspace(call.sessionId(), workspace)
            }
        },
    )
}

private const val WORKSPACE_PARAMETER: String = "workspace"
