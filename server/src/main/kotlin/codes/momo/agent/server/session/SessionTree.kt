package codes.momo.agent.server.session

import codes.momo.agent.server.storage.SessionConflictException
import codes.momo.agent.server.storage.pathTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SessionTree(val path: List<String>, val root: SessionEntry) {

    val rootId: String
        get() = path.first()

    val id: String
        get() = path.last()

    fun requireNoRunInFlight() {
        if (root.runtime?.hasRunInFlight() == true) {
            throw SessionConflictException("A run is in flight in the session's tree.")
        }
    }
}

internal suspend fun SessionRegistry.treeOf(id: String): SessionTree = withContext(Dispatchers.IO) {
    requireKnown(id)
    val path = store.pathTo(id)
    SessionTree(path, entry(path.first()))
}
