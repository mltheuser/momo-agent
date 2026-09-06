package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.liveSubagentBySessionId
import codes.momo.agent.subagentBySessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

internal class SessionConflictException(message: String) : RuntimeException(message)

internal class EventLogFailedException(cause: IOException) :
    RuntimeException("The session's event log failed: ${cause.message}", cause)

internal class InvalidRewindPointException(message: String) : RuntimeException(message)

@Suppress("TooManyFunctions")
internal class SessionRegistry(
    dataDir: Path,
    private val client: AiRouterClient,
) : AutoCloseable {

    private val store = SessionStore(dataDir)

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    // Announced from `finally` blocks, so it must never suspend; every signal says the same thing, so dropping is safe.
    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val sessionsChanged: SharedFlow<Unit> = changes.asSharedFlow()

    init {
        store.sessionIds().forEach { entries[it] = SessionEntry() }
    }

    suspend fun create(harnessPath: String, workspace: String, title: String? = null): SessionInfo =
        announcingChange {
            withContext(Dispatchers.IO) {
                val path = Path.of(harnessPath)
                val harness = Harness.load(path)
                val eventLog = store.eventLogForNewSession()
                val environment = ExecutionEnvironment(Path.of(workspace))
                val entry = SessionEntry()
                val logs = ConcurrentHashMap<String, PersistedEventLog>()
                val listener = TreeMemberListener(logs, entry, eventLog, sessionId = null)
                val agent = Agent(harness, client, environment, title ?: path.fileName.toString(), listener)
                logs[agent.sessionId] = eventLog
                try {
                    store.writeMetadata(agent.sessionId, SessionMetadata.Root(harnessPath, workspace))
                } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                    runCatching { eventLog.close() }
                    runCatching { store.delete(agent.sessionId) }
                    throw failure
                }
                entry.runtime = TreeRuntime(agent, environment, logs, ::announceChange)
                entries[agent.sessionId] = entry
                info(agent.sessionId)
            }
        }

    suspend fun list(workspace: String): List<SessionInfo> {
        val scope = normalizedWorkspace(workspace)
        return entries.keys.mapNotNull { id ->
            try {
                val metadata = withContext(Dispatchers.IO) { store.readMetadata(id) }
                when {
                    metadata !is SessionMetadata.Root -> null
                    normalizedWorkspace(metadata.workspace) != scope -> null
                    else -> info(id)
                }
            } catch (_: UnknownSessionException) {
                null
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null
            }
        }.sortedBy { it.createdAtMillis }
    }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) {
        entries.known(id)
        val events: List<AgentEvent>
        val position: TreePosition
        try {
            events = store.readEvents(id)
            position = store.position(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id)
        }

        val runtime = entries[position.path.first()]?.runtime
        SessionInfo(
            id = id,
            parent = position.path.dropLast(1).lastOrNull(),
            title = events.sessionTitle(),
            harnessPath = resolvedHarnessPath(position),
            workspace = position.root.workspace,
            privilege = runtime?.environment?.privilege,
            status = when {
                runtime == null -> SessionStatus.CLOSED
                runtime.isRunning(position.path) -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            },
            createdAtMillis = events.sessionCreatedAtMillis(),
            updatedAtMillis = events.sessionUpdatedAtMillis(),
            lastRun = events.lastRunStats(),
            modelSelection = events.modelSelection() ?: spawnPinnedSelection(position),
        )
    }

    private fun spawnPinnedSelection(position: TreePosition): ModelSelection? =
        position.path.dropLast(1).lastOrNull()
            ?.let { parentId -> storedSpawn(parentId, position.path.last()) }
            ?.let { spawn -> spawn.modelId?.let { ModelSelection(it, spawn.reasoningEffort) } }

    private fun resolvedHarnessPath(position: TreePosition): String {
        if (position.path.size == 1) {
            return position.root.harnessPath
        }
        val resolved = position.path.zipWithNext().fold(rootHarnessOrNull(position.root)) { harness, hop ->
            val (parentId, childId) = hop
            if (harness == null) {
                null
            } else {
                storedSpawn(parentId, childId)?.type?.let { type -> harness.subagents[type]?.harness }
            }
        }
        return resolved?.folder?.toString() ?: position.root.harnessPath
    }

    private fun rootHarnessOrNull(root: SessionMetadata.Root): Harness? = try {
        Harness.load(Path.of(root.harnessPath))
    } catch (_: HarnessValidationException) {
        null
    }

    private fun storedSpawn(parentId: String, childId: String): AgentEvent.SubagentSpawned? = try {
        store.readEvents(parentId)
            .filterIsInstance<AgentEvent.SubagentSpawned>()
            .lastOrNull { it.sessionId == childId }
    } catch (_: IOException) {
        null
    } catch (_: CorruptSessionException) {
        null
    }

    suspend fun rename(id: String, title: String): SessionInfo = recordMetadata(
        id,
        onAgent = { it.title = title },
        dormantEvent = { sequenceId, at -> AgentEvent.SessionRenamed(sequenceId, at, title) },
    )

    suspend fun selectModel(id: String, model: String, reasoningEffort: ReasoningEffort?): SessionInfo =
        recordMetadata(
            id,
            onAgent = { it.recordModelSelection(model, reasoningEffort) },
            dormantEvent = { sequenceId, at -> AgentEvent.ModelSelected(sequenceId, at, model, reasoningEffort) },
        )

    private suspend fun recordMetadata(
        id: String,
        onAgent: (Agent) -> Unit,
        dormantEvent: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
    ): SessionInfo {
        val (path, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock {
                val runtime = root.runtime
                if (runtime == null) {
                    appendToDormantLog(id, dormantEvent)
                } else {
                    val agent = runtime.agentAt(path) ?: throw UnknownSessionException(id)
                    withContext(Dispatchers.IO) { onAgent(agent) }
                }
            }
        }
        return info(id)
    }

    private suspend fun appendToDormantLog(
        id: String,
        event: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
    ) = withContext(Dispatchers.IO) {
        val nextSequenceId = try {
            store.readEvents(id).last().sequenceId + 1
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id)
        }
        val stamped = event(nextSequenceId, System.currentTimeMillis())
        try {
            store.eventLogFor(id).use { it.onEvent(stamped) }
        } catch (failure: IOException) {
            throw EventLogFailedException(failure)
        }
        entries.known(id).eventSignal.value = stamped.sequenceId
    }

    suspend fun close(id: String) {
        val (_, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock { teardown(root) }
        }
    }

    suspend fun stopRun(id: String) {
        val (path, root) = treeOf(entries, store, id)
        root.runtime?.stopRun(path)
    }

    suspend fun delete(id: String) {
        val target = entries.known(id)
        val root = withContext(Dispatchers.IO) {
            try {
                entries.known(store.position(id).path.first())
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                target
            }
        }
        announcingChange {
            root.mutex.withLock {
                teardown(root)
                withContext(NonCancellable + Dispatchers.IO) {
                    removeSubtree(id)
                }
            }
        }
    }

    private fun removeSubtree(id: String): List<String> {
        val members = store.subtreeIds(id)
        members.asReversed().forEach { member ->
            val memberEntry = entries.remove(member)
            store.delete(member)
            memberEntry?.eventSignal?.value = SESSION_DELETED_SIGNAL
        }
        return members
    }

    suspend fun rewind(id: String, firstDeletedSequenceId: Long): List<String> {
        val (path, root) = treeOf(entries, store, id)
        return announcingChange {
            root.mutex.withLock {
                if (root.runtime?.hasRunInFlight() == true) {
                    throw SessionConflictException("A run is in flight in the session's tree.")
                }
                val lastSurviving = lastSurvivorOfCutFrom(id, firstDeletedSequenceId)
                executeRewind(root, rootId = path.first(), id = id, lastSurviving = lastSurviving)
            }
        }
    }

    private suspend fun lastSurvivorOfCutFrom(id: String, firstDeletedSequenceId: Long): Long {
        val events = storedEvents(id)
        val named = events.firstOrNull { it.sequenceId == firstDeletedSequenceId }
        val below = events.filter { it.sequenceId < firstDeletedSequenceId }
        val refusal = when {
            named == null -> "Session $id has no event with sequence ID $firstDeletedSequenceId to cut from."
            named.isPreservedByACut() ->
                "Sequence ID $firstDeletedSequenceId names an event a cut preserves in session $id's log, " +
                    "so it cannot be an event to cut from."
            below.isEmpty() ->
                "Sequence ID $firstDeletedSequenceId opens session $id's log, and a cut must leave it standing."
            else -> null
        }
        if (refusal != null) {
            throw InvalidRewindPointException(refusal)
        }
        return below.last().sequenceId
    }

    private suspend fun storedEvents(id: String): List<AgentEvent> = withContext(Dispatchers.IO) {
        try {
            store.readEvents(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id)
        }
    }

    private suspend fun executeRewind(
        root: SessionEntry,
        rootId: String,
        id: String,
        lastSurviving: Long,
    ): List<String> = withContext(NonCancellable + Dispatchers.IO) {
        val plan = rewindPlan(id, lastSurviving) { sessionId ->
            try {
                store.readEvents(sessionId)
            } catch (_: NoSuchFileException) {
                null
            }
        }
        val runtime = root.runtime
        if (runtime == null) applyPlan(plan) else rewindAttachedTree(root, rootId, runtime, plan)
    }

    private suspend fun rewindAttachedTree(
        root: SessionEntry,
        rootId: String,
        runtime: TreeRuntime,
        plan: RewindPlan,
    ): List<String> {
        root.runtime = null
        runtime.abortRuns()
        runtime.closeLogs()
        val harness = Harness.load(Path.of(store.position(rootId).root.harnessPath))
        val deleted = applyPlan(plan)
        runCatching { root.runtime = loadTree(root, rootId, harness, runtime.environment) }
        return deleted
    }

    private fun applyPlan(plan: RewindPlan): List<String> {
        val deleted = plan.deletedSubtreeRoots.flatMap { removeSubtree(it) }
        val gone = deleted.toSet()
        plan.cuts.reversed().forEach { (sessionId, lastSurviving) ->
            if (sessionId !in gone) {
                val rewound = store.rewindEvents(sessionId, lastSurviving)
                entries[sessionId]?.let { entry ->
                    entry.truncations.value += 1
                    entry.eventSignal.value = rewound.sequenceId
                }
            }
        }
        return deleted
    }

    suspend fun startRun(id: String, prompt: String, settings: RunSettings) {
        val (path, root) = treeOf(entries, store, id)
        root.mutex.withLock {
            launchRunLocked(root, path) { agent -> agent.send(prompt, settings) }
        }
    }

    suspend fun retryRun(id: String) {
        val (path, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock {
                if (root.runtime?.hasRunInFlight() == true) {
                    throw SessionConflictException("A run is in flight in the session's tree.")
                }
                val plan = retryPlan(storedEvents(id))
                executeRewind(root, rootId = path.first(), id = id, lastSurviving = plan.lastSurvivingSequenceId)
                launchRunLocked(root, path) { agent -> agent.retry(plan.settings) }
            }
        }
    }

    private suspend fun launchRunLocked(
        root: SessionEntry,
        path: List<String>,
        run: suspend (Agent) -> RunResult,
    ) {
        val attached = root.runtime
        val runtime = attached ?: rebuild(root, path.first()).also { root.runtime = it }
        try {
            val agent = runtime.agentAt(path) ?: throw UnknownSessionException(path.last())
            runtime.launchRun(agent, run)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            if (attached == null) {
                teardown(root)
            }
            throw failure
        }
    }

    fun eventsAfter(id: String, afterSequenceId: Long): Flow<StoredEvent> {
        val entry = entries.known(id)
        return store.tailEvents(id, entry.eventSignal, entry.truncations, afterSequenceId)
    }

    fun requireKnown(id: String) {
        entries.known(id)
    }

    suspend fun requireInWorkspace(id: String, workspace: String): Unit = withContext(Dispatchers.IO) {
        entries.known(id)
        val root = try {
            store.position(id).root
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id)
        } catch (_: CorruptSessionException) {
            throw UnknownSessionException(id)
        }
        if (normalizedWorkspace(root.workspace) != normalizedWorkspace(workspace)) {
            throw UnknownSessionException(id)
        }
    }

    private fun announceChange() {
        changes.tryEmit(Unit)
    }

    private suspend fun <T> announcingChange(block: suspend () -> T): T = try {
        block()
    } finally {
        announceChange()
    }

    override fun close() {
        runBlocking {
            entries.keys.forEach { id ->
                runCatching { close(id) }
            }
        }
    }

    private suspend fun rebuild(entry: SessionEntry, id: String): TreeRuntime = withContext(Dispatchers.IO) {
        val metadata = try {
            store.position(id).root
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id)
        }
        val harness = Harness.load(Path.of(metadata.harnessPath))
        loadTree(entry, id, harness, ExecutionEnvironment(Path.of(metadata.workspace)))
    }

    private fun loadTree(
        entry: SessionEntry,
        id: String,
        harness: Harness,
        environment: ExecutionEnvironment,
    ): TreeRuntime {
        val events = store.readEvents(id)
        val eventLog = store.eventLogFor(id)
        val logs = ConcurrentHashMap<String, PersistedEventLog>()
        logs[id] = eventLog
        val agent = Agent.load(events, harness, client, environment, TreeMemberListener(logs, entry, eventLog, id))
        return TreeRuntime(agent, environment, logs, ::announceChange)
    }

    private inner class TreeMemberListener(
        private val logs: ConcurrentHashMap<String, PersistedEventLog>,
        private val entry: SessionEntry,
        private val log: PersistedEventLog,
        sessionId: String?,
    ) : AgentEventListener {

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

private fun Map<String, SessionEntry>.known(id: String): SessionEntry =
    this[id] ?: throw UnknownSessionException(id)

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

private suspend fun teardown(rootEntry: SessionEntry) {
    val runtime = rootEntry.runtime ?: return
    rootEntry.runtime = null

    withContext(NonCancellable + Dispatchers.IO) {
        runtime.abortRuns()
        runtime.closeLogs()
    }
}

private class SessionEntry {

    val mutex = Mutex()

    val eventSignal = MutableStateFlow(BEFORE_FIRST_EVENT)

    val truncations = MutableStateFlow(0L)

    @Volatile
    var runtime: TreeRuntime? = null
}

private class TreeRuntime(
    private val rootAgent: Agent,
    val environment: ExecutionEnvironment,
    private val logs: ConcurrentHashMap<String, PersistedEventLog>,
    private val announceChange: () -> Unit,
) {

    private val job = SupervisorJob()

    private val scope = CoroutineScope(job + Dispatchers.Default)

    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    suspend fun agentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.subagentBySessionId(childId) }

    suspend fun isRunning(path: List<String>): Boolean =
        path.last() in activeRuns || liveAgentAt(path)?.isRunning == true

    fun hasRunInFlight(): Boolean = activeRuns.isNotEmpty()

    private suspend fun liveAgentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.liveSubagentBySessionId(childId) }

    fun launchRun(agent: Agent, run: suspend (Agent) -> RunResult) {
        logs[agent.sessionId]?.failure?.let { throw EventLogFailedException(it) }
        claimRun(agent)

        announceChange()
        scope.launch {
            try {
                run(agent)
            } finally {
                activeRuns.remove(agent.sessionId)
                announceChange()
            }
        }
    }

    suspend fun abortRuns() {
        job.cancelAndJoin()
    }

    suspend fun stopRun(path: List<String>) {
        liveAgentAt(path)?.stop()
    }

    fun closeLogs() {
        logs.values.map { runCatching { it.close() } }
            .firstNotNullOfOrNull { it.exceptionOrNull() }
            ?.let { throw it }
    }

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

private class TreePosition(val path: List<String>, val root: SessionMetadata.Root)

private const val RUN_ACTIVE_MESSAGE = "A run is already active on this session."
