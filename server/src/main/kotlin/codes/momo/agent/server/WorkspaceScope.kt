package codes.momo.agent.server

import codes.momo.agent.server.session.SessionRegistry
import codes.momo.agent.server.storage.UnknownSessionException
import codes.momo.agent.server.storage.ifReadable
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** The scope a request names, spelled as a session records its workspace: the folder's real path. */
internal fun ApplicationCall.workspaceParameter(): String? {
    val raw = request.queryParameters[WORKSPACE_PARAMETER]?.requireNotBlank("workspace") ?: return null
    val named = Path.of(raw)
    if (!named.isAbsolute) {
        throw BadRequestException("A workspace must be an absolute path, not: $raw")
    }
    return try {
        named.toRealPath().toString()
    } catch (_: IOException) {
        named.normalize().toString()
    }
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

private suspend fun SessionRegistry.requireInWorkspace(id: String, workspace: String): Unit =
    withContext(Dispatchers.IO) {
        requireKnown(id)
        if (store.ifReadable { readSessionStarted(id) }?.workspace != workspace) {
            throw UnknownSessionException(id)
        }
    }

private const val WORKSPACE_PARAMETER: String = "workspace"
