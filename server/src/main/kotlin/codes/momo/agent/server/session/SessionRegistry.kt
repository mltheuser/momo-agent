package codes.momo.agent.server.session

import ai.router.sdk.AiRouterClient
import codes.momo.agent.server.change.ChangeSignal
import codes.momo.agent.server.storage.SessionStore
import codes.momo.agent.server.storage.UnknownSessionException
import codes.momo.agent.server.storage.forest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class SessionRegistry(val store: SessionStore, val client: AiRouterClient, val changes: ChangeSignal) {

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    init {
        store.sessionIds().forEach { entries[it] = SessionEntry() }
    }

    val ids: Set<String>
        get() = entries.keys

    fun entry(id: String): SessionEntry = entries[id] ?: throw UnknownSessionException(id)

    fun requireKnown(id: String) {
        entry(id)
    }

    fun entryOrNull(id: String): SessionEntry? = entries[id]

    fun register(id: String) {
        entries.computeIfAbsent(id) { SessionEntry() }
    }

    fun removeSubtree(id: String): List<String> {
        val members = store.forest().subtreeOf(id)
        members.asReversed().forEach { member ->
            entries.remove(member)
            store.delete(member)
        }
        return members
    }
}

internal suspend fun SessionRegistry.events(id: String): String {
    settledTreeOf(id)
    return withContext(Dispatchers.IO) {
        store.readLines(id).joinToString(separator = ",", prefix = "[", postfix = "]") { it.json }
    }
}

internal class SessionEntry {

    val mutex: Mutex = Mutex()

    @Volatile
    var run: ActiveRun? = null
}
