package codes.momo.agent.server.session

import codes.momo.agent.server.storage.SessionConflictException
import codes.momo.agent.server.storage.ifReadable
import codes.momo.agent.server.storage.pathTo
import codes.momo.agent.server.storage.repairTornRun
import codes.momo.agent.server.storage.spawnedTreeIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SessionTree(val path: List<String>, val root: SessionEntry) {

    val id: String
        get() = path.last()

    fun requireNoRunInFlight() {
        if (root.run != null) {
            throw SessionConflictException("A run is in flight in the session's tree.")
        }
    }
}

internal suspend fun SessionRegistry.treeOf(id: String): SessionTree = withContext(Dispatchers.IO) {
    requireKnown(id)
    val path = store.pathTo(store.readSessionStarted(id))
    SessionTree(path, entry(path.first())).also { settle(it) }
}

private suspend fun SessionRegistry.settle(tree: SessionTree) {
    tree.root.mutex.withLock {
        if (tree.root.run != null) {
            return
        }
        store.spawnedTreeIds(tree.path.first()).forEach { member ->
            val repairs = store.ifReadable { repairTornRun(member, System.currentTimeMillis()) }.orEmpty()
            repairs.lastOrNull()?.let { entryOrNull(member)?.log?.appended(it.sequenceId) }
        }
    }
}
