package codes.momo.agent.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import java.nio.file.Path

/*
 * A session belongs to the workspace folder it works in, and that folder is
 * the scope every request is read in: a client sees only its own workspace's
 * sessions. The scope travels as a query parameter rather than a header
 * because `Headers` accepts only Latin-1 — a project path with a non-ASCII
 * character would throw on every request — while a query parameter
 * percent-encodes safely.
 */

/**
 * [path] in the form two workspaces are compared in: lexically absolute and
 * normalized, so a trailing slash and a `.`/`..` segment cannot spell a second
 * scope for one folder.
 *
 * Symlinks are deliberately left unresolved, twice over. The folder the
 * agent's commands actually run in is the unresolved path
 * ([codes.momo.agent.environment.ExecutionEnvironment]), so resolving
 * here would compare against something the session never used; and `realpath`
 * touches the filesystem, which would make a session whose workspace folder
 * was *deleted* unlistable and undeletable. The cost is that two spellings of
 * one folder — a symlink and its target — are two scopes.
 */
internal fun normalizedWorkspace(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

/**
 * The request's `workspace` query parameter. Absent reads as absent; present
 * but blank or relative is a client bug worth failing on rather than silently
 * widening the scope.
 *
 * @throws BadRequestException when the parameter is present but blank or relative.
 */
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

/**
 * The request's `workspace` query parameter, required.
 *
 * @throws BadRequestException when it is missing, blank or relative. Required
 *   on purpose where a listing is served: an unscoped listing is meaningless
 *   now that a session belongs to a folder, so a client that forgets the scope
 *   must fail loudly rather than quietly see every project on the machine.
 */
internal fun ApplicationCall.requiredWorkspace(): String =
    workspaceParameter() ?: throw BadRequestException(
        "A session listing is scoped to one workspace: name it with a ?$WORKSPACE_PARAMETER=<absolute path>.",
    )

/**
 * Confines the routes below to one workspace: a `workspace` parameter that is
 * not the session tree root's own makes the session read as unknown — a `404`,
 * never a `403`, since a scope that leaked which IDs exist elsewhere would be
 * no scope at all.
 *
 * The parameter is **optional** here, unlike on a listing: an unscoped read of
 * a session named by its ID is exactly what a curl session and a server test
 * do, and the extension's guarantee holds as long as the extension always
 * sends it.
 *
 * A route-scoped plugin rather than a check per handler, following the
 * event stream's own unknown-session guard: it covers the SSE route too, where
 * the handler runs with the `200` already committed and a `404` no longer
 * possible. Ktor runs a parent route's plugins before the child's, so that
 * guard still runs after this one.
 */
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

/** The query parameter naming the workspace a request is scoped to. */
private const val WORKSPACE_PARAMETER: String = "workspace"
