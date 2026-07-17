package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.liveSubagentBySessionId
import codes.momo.agent.subagentBySessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Thrown by every operation naming a session the registry does not know. */
internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

/** Thrown when an operation does not fit the session's current state, e.g. a prompt racing an active run. */
internal class SessionConflictException(message: String) : RuntimeException(message)

/** Thrown when a session's event log stopped persisting. */
internal class EventLogFailedException(cause: IOException) :
    RuntimeException("The session's event log failed: ${cause.message}", cause)

/**
 * All sessions the server knows, live or dormant. A session *is* its stored
 * log and metadata (see [SessionStore]); the running agents of a subagent
 * tree plus their one shared environment are an ephemeral [TreeRuntime]
 * attached to the tree's root entry — dropped only on explicit close,
 * delete, or shutdown, and rebuilt on demand when a prompt arrives (see
 * [startRun]). Startup indexes the data directory, so sessions stored by
 * an earlier process appear as dormant entries — a restart is an implicit
 * close of everything that was live.
 *
 * Tree lifecycle transitions serialize on the root entry's mutex — the
 * only mutex ever locked, so there is no lock ordering to get wrong;
 * blocking work (harness and store IO, environment construction — possibly
 * a slow image pull) runs on the IO dispatcher.
 */
@Suppress("TooManyFunctions") // One cohesive surface over the shared entry map and the root-mutex discipline.
internal class SessionRegistry(
    dataDir: Path,
    private val client: AiRouterClient,
) : AutoCloseable {

    private val store = SessionStore(dataDir)

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    init {
        store.sessionIds().forEach { entries[it] = SessionEntry() }
    }

    /**
     * Creates a live session from the harness folder at [harnessPath] and a
     * fresh environment built from [spec], returning its first [SessionInfo].
     * [title] defaults to the harness folder's name.
     *
     * @throws codes.momo.agent.harness.HarnessValidationException when the
     *   harness folder is invalid.
     * @throws codes.momo.agent.environment.EnvironmentStartupException when
     *   the environment cannot be built.
     */
    suspend fun create(harnessPath: String, spec: EnvironmentSpec, title: String? = null): SessionInfo =
        withContext(Dispatchers.IO) {
            val path = Path.of(harnessPath)
            val harness = Harness.load(path)
            val eventLog = store.eventLogForNewSession()
            val environment = spec.build()
            val entry = SessionEntry()
            val logs = ConcurrentHashMap<String, PersistedEventLog>()
            val agent = closingOnFailure(environment) {
                val listener = TreeMemberListener(logs, entry, eventLog, sessionId = null)
                Agent(harness, client, environment, title ?: path.fileName.toString(), listener)
            }
            logs[agent.sessionId] = eventLog
            try {
                store.writeMetadata(agent.sessionId, SessionMetadata.Root(harnessPath, spec))
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Without metadata the session can never be rebuilt: discard
                // every artifact instead of leaking the live environment.
                runCatching { eventLog.close() }
                runCatching { environment.close() }
                runCatching { store.delete(agent.sessionId) }
                throw failure
            }
            entry.runtime = TreeRuntime(agent, environment, logs)
            entries[agent.sessionId] = entry
            info(agent.sessionId)
        }

    /** Root sessions only: children are discovered through their parent's `subagent_spawned` events. */
    suspend fun list(): List<SessionInfo> = entries.keys.mapNotNull { id ->
        try {
            when (withContext(Dispatchers.IO) { store.readMetadata(id) }) {
                is SessionMetadata.Root -> info(id)
                is SessionMetadata.Child -> null
            }
        } catch (_: UnknownSessionException) {
            null // Deleted while listing.
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            null // Unreadable stored state must not hide the healthy sessions; get(id) reports it.
        }
    }.sortedBy { it.createdAtMillis }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) {
        entries.known(id)
        val events: List<AgentEvent>
        val position: TreePosition
        try {
            events = store.readEvents(id)
            position = store.position(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted between lookup and read.
        }
        // Status comes from the tree's runtime, never from the log's tail:
        // an aborted run leaves no RunFinished behind to read.
        val runtime = entries[position.path.first()]?.runtime
        SessionInfo(
            id = id,
            parent = position.path.dropLast(1).lastOrNull(),
            title = events.sessionTitle(),
            harnessPath = position.root.harnessPath,
            environment = position.root.environment,
            status = when {
                runtime == null -> SessionStatus.CLOSED
                runtime.isRunning(position.path) -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            },
            favorite = position.root.favorite,
            createdAtMillis = events.sessionCreatedAtMillis(),
            updatedAtMillis = events.sessionUpdatedAtMillis(),
            lastRun = events.lastRunStats(),
        )
    }

    /**
     * Sets [id]'s title to [title], returning the updated info. A member of
     * an attached tree renames through its agent; a dormant session gets
     * the [AgentEvent.SessionRenamed] appended straight to its stored log —
     * a rename never attaches a runtime. The root's mutex serializes both
     * paths, so a direct append cannot race a prompt rebuilding the tree.
     */
    suspend fun rename(id: String, title: String): SessionInfo {
        val (path, root) = treeOf(entries, store, id)
        root.mutex.withLock {
            val runtime = root.runtime
            if (runtime == null) {
                appendRenamed(id, title)
            } else {
                val agent = runtime.agentAt(path) ?: throw UnknownSessionException(id)
                withContext(Dispatchers.IO) { agent.title = title }
            }
        }
        return info(id)
    }

    /**
     * Sets [id]'s [SessionInfo.favorite] and returns the updated info.
     * Never attaches a runtime, so a `closed` session stays closed.
     */
    suspend fun setFavorite(id: String, favorite: Boolean): SessionInfo {
        val (path, root) = treeOf(entries, store, id)
        root.mutex.withLock {
            withContext(Dispatchers.IO) {
                val rootId = path.first()
                val metadata = try {
                    store.position(rootId).root
                } catch (_: NoSuchFileException) {
                    throw UnknownSessionException(id) // Deleted while waiting on the mutex.
                }
                store.writeMetadata(rootId, metadata.copy(favorite = favorite))
            }
        }
        return info(id)
    }

    /** Appends an [AgentEvent.SessionRenamed] to dormant [id]'s stored log; the caller holds the root's mutex. */
    private suspend fun appendRenamed(id: String, title: String) = withContext(Dispatchers.IO) {
        val nextSequenceId = try {
            // The next sequence ID exactly as the lib's restore path —
            // restoredSession in SessionState.kt — derives it; the two
            // must always agree.
            store.readEvents(id).last().sequenceId + 1
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
        val event = AgentEvent.SessionRenamed(nextSequenceId, System.currentTimeMillis(), title)
        try {
            store.eventLogFor(id).use { it.onEvent(event) }
        } catch (failure: IOException) {
            throw EventLogFailedException(failure)
        }
        entries.known(id).eventSignal.value = event.sequenceId
    }

    /**
     * Closes [id]'s whole tree: cancels every in-flight run in it, closes
     * the open event logs, closes the one environment (a container copies
     * its workspace back to the host), and drops the runtime attachment.
     * Every member stays stored and resumable. Closing a dormant tree is a
     * no-op.
     *
     * @throws codes.momo.agent.environment.EnvironmentFailureException when
     *   the environment's teardown failed; the tree is closed regardless.
     */
    suspend fun close(id: String) {
        val (_, root) = treeOf(entries, store, id)
        root.mutex.withLock { teardown(root) }
    }

    /**
     * Closes [id]'s whole tree, then removes [id]'s subtree: the session
     * itself and every stored descendant lose their registry entries and
     * stored artifacts, and their event streams end. The rest of the tree
     * stays stored, closed.
     */
    suspend fun delete(id: String) {
        val target = entries.known(id)
        val root = withContext(Dispatchers.IO) {
            try {
                entries.known(store.position(id).path.first())
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Unresolvable ancestry — unreadable metadata or a broken
                // parent link — must not make a session undeletable; such a
                // tree can never be attached, so the target's own entry
                // serializes the removal and there is nothing to tear down.
                target
            }
        }
        root.mutex.withLock {
            teardown(root)
            withContext(NonCancellable + Dispatchers.IO) {
                // Leaves first: a removal cut short by process death leaves
                // a consistent tree, never children orphaned by a missing
                // parent link.
                store.subtreeIds(id).asReversed().forEach { member ->
                    val memberEntry = entries.remove(member)
                    store.delete(member)
                    memberEntry?.eventSignal?.value = SESSION_DELETED_SIGNAL
                }
            }
        }
    }

    /**
     * Starts a run over [prompt] on [id] under [settings], rebuilding its
     * tree's runtime when dormant and reviving just the chain from the
     * root down to [id].
     * Attach, navigation, and start happen under the root entry's mutex, so
     * a concurrent close cannot void an accepted prompt between them.
     *
     * @throws EventLogFailedException when the session's log stopped
     *   persisting: without it a run would leave no record.
     * @throws SessionConflictException when the session already has a run
     *   in flight — its own, or a parent-driven one.
     */
    suspend fun startRun(id: String, prompt: String, settings: RunSettings) {
        val (path, root) = treeOf(entries, store, id)
        root.mutex.withLock {
            val attached = root.runtime
            val runtime = attached ?: rebuild(root, path.first()).also { root.runtime = it }
            try {
                val agent = runtime.agentAt(path) ?: throw UnknownSessionException(id)
                runtime.startRun(agent, prompt, settings)
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // A failed prompt must not leave behind the runtime it
                // attached — a member unreachable from its parent's log (a
                // torn spawn tail) or a failed revival throws here.
                if (attached == null) {
                    teardown(root)
                }
                throw failure
            }
        }
    }

    /**
     * Stream of [id]'s stored events via [SessionStore.tailEvents];
     * subscribing never attaches a dormant session.
     */
    fun eventsAfter(id: String, afterSequenceId: Long): Flow<StoredEvent> =
        store.tailEvents(id, entries.known(id).eventSignal, afterSequenceId)

    /** @throws UnknownSessionException when [id] names no known session. */
    fun requireKnown(id: String) {
        entries.known(id)
    }

    /** Closes every live tree; the stored sessions stay for the next process. */
    override fun close() {
        runBlocking {
            entries.keys.forEach { id ->
                runCatching { close(id) }
            }
        }
    }

    /** Rebuilds the tree runtime rooted at [id] from its stored artifacts; the caller holds the root's mutex. */
    private suspend fun rebuild(entry: SessionEntry, id: String): TreeRuntime = withContext(Dispatchers.IO) {
        val metadata = try {
            store.position(id).root
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
        val harness = Harness.load(Path.of(metadata.harnessPath))
        val events = store.readEvents(id)
        val eventLog = store.eventLogFor(id)
        val environment = metadata.environment.build()
        val logs = ConcurrentHashMap<String, PersistedEventLog>()
        logs[id] = eventLog
        val agent = closingOnFailure(environment) {
            Agent.load(events, harness, client, environment, TreeMemberListener(logs, entry, eventLog, id))
        }
        TreeRuntime(agent, environment, logs)
    }

    /**
     * One tree member's listener: the log first — the event is on disk when
     * the signal fires — then the wake-up. Every subagent the member
     * constructs (a spawn, or a revival) becomes a session here, before its
     * spawn event is observable: the child gets a registry entry, metadata
     * naming this member as its parent, and an open log registered in the
     * tree's [logs] for teardown — reusing whatever of that already exists.
     * Never throws: a child that cannot be persisted merely goes unobserved
     * instead of failing the spawn. Revival reads the stored logs back
     * through [storedEventsFor]; a deleted session yields null there.
     */
    private inner class TreeMemberListener(
        private val logs: ConcurrentHashMap<String, PersistedEventLog>,
        private val entry: SessionEntry,
        private val log: PersistedEventLog,
        sessionId: String?,
    ) : AgentEventListener {

        // A fresh session's ID becomes known with its first event.
        @Volatile
        private var sessionId: String? = sessionId

        override fun onEvent(event: AgentEvent) {
            if (sessionId == null && event is AgentEvent.SessionStarted) {
                sessionId = event.sessionId
            }
            log.onEvent(event)
            entry.eventSignal.value = event.sequenceId
        }

        override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener = try {
            attachChild(parentId = checkNotNull(this.sessionId), childId = sessionId)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            AgentEventListener { }
        }

        override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = withContext(Dispatchers.IO) {
            try {
                store.readEvents(sessionId)
            } catch (_: NoSuchFileException) {
                null
            }
        }

        private fun attachChild(parentId: String, childId: String): TreeMemberListener {
            val childEntry = entries[childId] ?: SessionEntry().also { fresh ->
                store.writeMetadata(childId, SessionMetadata.Child(parentId))
                entries[childId] = fresh
            }
            val childLog = logs.computeIfAbsent(childId) { store.eventLogFor(childId) }
            return TreeMemberListener(logs, childEntry, childLog, childId)
        }
    }
}

/** The entry of [id]; sessions the registry does not know throw. */
private fun Map<String, SessionEntry>.known(id: String): SessionEntry =
    this[id] ?: throw UnknownSessionException(id)

/**
 * [id]'s path from its tree root — walked through the stored parent links —
 * plus the root's registry entry, whose mutex serializes the tree's
 * lifecycle. Metadata vanishing mid-walk reads as a deleted session.
 */
private suspend fun treeOf(
    entries: Map<String, SessionEntry>,
    store: SessionStore,
    id: String,
): Pair<List<String>, SessionEntry> = withContext(Dispatchers.IO) {
    entries.known(id)
    val path = try {
        store.position(id).path
    } catch (_: NoSuchFileException) {
        throw UnknownSessionException(id)
    }
    path to entries.known(path.first())
}

/** [id]'s position in its tree, resolved through the stored parent links. */
private fun SessionStore.position(id: String): TreePosition {
    val ancestry = mutableListOf(id)
    var current = readMetadata(id)
    while (current is SessionMetadata.Child) {
        check(current.parent !in ancestry) { "Stored session $id has a parent cycle in its metadata." }
        ancestry += current.parent
        current = readMetadata(current.parent)
    }
    return TreePosition(ancestry.asReversed(), current as SessionMetadata.Root)
}

/** [id] plus its stored descendants, discovered through the metadata parent links. */
private fun SessionStore.subtreeIds(id: String): List<String> {
    val childrenByParent = sessionIds().groupBy { sessionId ->
        (runCatching { readMetadata(sessionId) }.getOrNull() as? SessionMetadata.Child)?.parent
    }
    val subtree = mutableListOf(id)
    var index = 0
    while (index < subtree.size) {
        subtree += childrenByParent[subtree[index]].orEmpty()
        index++
    }
    return subtree
}

/** Tears down [rootEntry]'s tree runtime if attached; the caller holds the root's mutex. */
private suspend fun teardown(rootEntry: SessionEntry) {
    val runtime = rootEntry.runtime ?: return
    rootEntry.runtime = null
    // Shielded: a caller's cancellation (a client disconnecting
    // mid-request) must not abandon a live environment.
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            runtime.abortRuns()
            runtime.closeLogs()
        } finally {
            runtime.environment.close()
        }
    }
}

/** Cleanup-and-rethrow: a failed construction must not leak [environment]. */
private inline fun <T> closingOnFailure(environment: ExecutionEnvironment, block: () -> T): T = try {
    block()
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    runCatching { environment.close() }
    throw failure
}

/**
 * One registry slot. [eventSignal] carries the latest logged sequenceId as
 * the wake-up event tails wait on; it outlives the runtime so subscribers
 * of a dormant session see the events of a later reattachment. [runtime]
 * and [mutex] matter on tree roots only: the runtime belongs to the whole
 * tree, and every tree lifecycle transition serializes on its root's mutex.
 */
private class SessionEntry {

    val mutex = Mutex()

    val eventSignal = MutableStateFlow(BEFORE_FIRST_EVENT)

    @Volatile
    var runtime: TreeRuntime? = null
}

/**
 * The ephemeral runtime attachment of a live subagent tree: the root
 * agent, the one environment every member executes in, and the open event
 * log of every member that has been live under this attachment.
 */
private class TreeRuntime(
    private val rootAgent: Agent,
    val environment: ExecutionEnvironment,
    private val logs: ConcurrentHashMap<String, PersistedEventLog>,
) {

    private val job = SupervisorJob()

    private val scope = CoroutineScope(job + Dispatchers.Default)

    /** Session IDs with a server-started run in flight. */
    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    /** The live-or-revived agent at [path] (root ID first); null when a link is unknown to its parent. */
    suspend fun agentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.subagentBySessionId(childId) }

    /**
     * Whether [path]'s member has a run in flight: a server-started one, or
     * one on the live agent. Walks live links only — a member without a
     * live agent cannot be running, and a status read must not revive one.
     */
    suspend fun isRunning(path: List<String>): Boolean =
        path.last() in activeRuns || liveAgentAt(path)?.isRunning == true

    private suspend fun liveAgentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.liveSubagentBySessionId(childId) }

    /**
     * The one way to drive a member of the tree: starts a run over [prompt]
     * as a child of the tree's scope — so [abortRuns] reaches it — and
     * returns as soon as it is started. The run's outcome is never
     * returned; its [AgentEvent.RunFinished] log entry is the record.
     */
    fun startRun(agent: Agent, prompt: String, settings: RunSettings) {
        logs[agent.sessionId]?.failure?.let { throw EventLogFailedException(it) }
        claimRun(agent)
        scope.launch { agent.send(prompt, settings) }.invokeOnCompletion { activeRuns.remove(agent.sessionId) }
    }

    /** Cancels every in-flight run in the tree and waits for the aborts; the runtime takes no further runs. */
    suspend fun abortRuns() {
        job.cancelAndJoin()
    }

    /** Closes every member's open log; the first failure propagates once all are closed. */
    fun closeLogs() {
        logs.values.map { runCatching { it.close() } }
            .firstNotNullOfOrNull { it.exceptionOrNull() }
            ?.let { throw it }
    }

    /**
     * Claims [agent]'s run slot, conflicting on an already-active run. The
     * unclaimed parent-driven case is a pre-check: should such a run start
     * concurrently, the agent's own busy guard fails the claimed run
     * without wedging the slot.
     */
    private fun claimRun(agent: Agent) {
        if (!activeRuns.add(agent.sessionId)) {
            throw SessionConflictException(RUN_ACTIVE_MESSAGE)
        }
        if (agent.isRunning) {
            activeRuns.remove(agent.sessionId)
            throw SessionConflictException(RUN_ACTIVE_MESSAGE)
        }
    }
}

/** [id]'s place in its subagent tree: the IDs from the root down to it, and the root's stored facts. */
private class TreePosition(val path: List<String>, val root: SessionMetadata.Root)

private const val RUN_ACTIVE_MESSAGE = "A run is already active on this session."
