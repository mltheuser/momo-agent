package codes.momo.agent.server.storage

/** The stored sessions as trees. A session whose log names no stored parent, or is corrupt, is a root. */
internal class SessionForest(private val parentOf: Map<String, String?>) {

    private val childrenOf: Map<String?, List<String>> = parentOf.keys.groupBy { parentOf[it] }

    val roots: List<String>
        get() = parentOf.keys.filter { parentOf[it] !in parentOf }

    fun subtreeOf(id: String): List<String> {
        val subtree = mutableListOf(id)
        var index = 0
        while (index < subtree.size) {
            subtree += childrenOf[subtree[index]].orEmpty()
            index++
        }
        return subtree
    }
}

internal fun SessionStore.forest(): SessionForest =
    SessionForest(sessionIds().associateWith { id -> ifReadable { readSessionStarted(id) }?.parent })
