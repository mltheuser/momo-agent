package codes.momo.agent.server

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

internal fun SessionStore.pathTo(id: String): List<String> {
    val ancestry = mutableListOf(id)
    var parent = readSessionStarted(id).parent
    while (parent != null) {
        check(parent !in ancestry) { "Stored session $id has a parent cycle." }
        ancestry += parent
        parent = readSessionStarted(parent).parent
    }
    return ancestry.asReversed()
}

internal fun SessionStore.subtreeIds(id: String): List<String> {
    val childrenByParent = sessionIds().groupBy { sessionId ->
        runCatching { readSessionStarted(sessionId).parent }.getOrNull()
    }
    val subtree = mutableListOf(id)
    var index = 0
    while (index < subtree.size) {
        subtree += childrenByParent[subtree[index]].orEmpty()
        index++
    }
    return subtree
}
