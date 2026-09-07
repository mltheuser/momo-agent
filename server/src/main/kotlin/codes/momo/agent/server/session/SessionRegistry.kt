package codes.momo.agent.server.session

import ai.router.sdk.AiRouterClient
import codes.momo.agent.server.storage.EventLogSignal
import codes.momo.agent.server.storage.LogLine
import codes.momo.agent.server.storage.SessionStore
import codes.momo.agent.server.storage.UnknownSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class SessionRegistry(dataDir: Path, val client: AiRouterClient) {

    val store: SessionStore = SessionStore(dataDir)

    val changes: ChangeSignal = ChangeSignal()

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

    fun entryFor(id: String): SessionEntry = entries.computeIfAbsent(id) { SessionEntry() }

    fun register(id: String, entry: SessionEntry) {
        entries[id] = entry
    }

    fun removeSubtree(id: String): List<String> {
        val members = store.subtreeIds(id)
        members.asReversed().forEach { member ->
            val entry = entries.remove(member)
            store.delete(member)
            entry?.log?.deleted()
        }
        return members
    }

    fun eventsAfter(id: String, afterSequenceId: Long): Flow<LogLine> =
        store.tail(id, entry(id).log, afterSequenceId)
}

internal class SessionEntry {

    val mutex: Mutex = Mutex()

    val log: EventLogSignal = EventLogSignal()

    @Volatile
    var runtime: TreeRuntime? = null

    suspend fun detachRuntime(): TreeRuntime? {
        val detached = runtime ?: return null
        runtime = null
        withContext(NonCancellable + Dispatchers.IO) {
            detached.abortRuns()
            detached.closeLogs()
        }
        return detached
    }
}
