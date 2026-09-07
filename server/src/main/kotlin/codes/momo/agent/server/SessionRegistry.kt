package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooManyFunctions")
internal class SessionRegistry(
    dataDir: Path,
    val client: AiRouterClient,
) : AutoCloseable {

    val store: SessionStore = SessionStore(dataDir)

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    val ids: Set<String>
        get() = entries.keys

    fun entry(id: String): SessionEntry = entries[id] ?: throw UnknownSessionException(id)

    fun entryOrNull(id: String): SessionEntry? = entries[id]

    fun entryFor(id: String): SessionEntry = entries.computeIfAbsent(id) { SessionEntry() }

    fun requireKnown(id: String) {
        entry(id)
    }

    val changes: ChangeSignal = ChangeSignal()

    init {
        store.sessionIds().forEach { entries[it] = SessionEntry() }
    }

    suspend fun create(harnessPath: String, workspace: String, title: String? = null): SessionInfo =
        changes.announcing {
            withContext(Dispatchers.IO) {
                val path = Path.of(harnessPath)
                val harness = Harness.load(path)
                val eventLog = store.eventLogForNewSession()
                val environment = ExecutionEnvironment(Path.of(workspace))
                val root = SessionEntry()
                val runtime = buildTreeRuntime(root, environment, eventLog) { listener ->
                    Agent(harness, client, environment, title ?: path.fileName.toString(), listener)
                }
                eventLog.failure?.let { failure ->
                    runCatching { eventLog.close() }
                    runCatching { store.delete(runtime.rootId) }
                    throw EventLogFailedException(failure)
                }
                root.runtime = runtime
                entries[runtime.rootId] = root
                info(runtime.rootId)
            }
        }

    suspend fun list(workspace: String): List<SessionInfo> {
        val scope = normalizedWorkspace(workspace)
        return ids.mapNotNull { id ->
            try {
                val started = withContext(Dispatchers.IO) { store.readSessionStarted(id) }
                if (started.parent == null && normalizedWorkspace(started.workspace) == scope) info(id) else null
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null
            }
        }.sortedBy { it.createdAtMillis }
    }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) {
        requireKnown(id)
        val path = store.pathTo(id)
        val events = store.readEvents(id)
        val started = events.sessionStarted()
        val runtime = entries[path.first()]?.runtime
        SessionInfo(
            id = id,
            parent = started.parent,
            title = events.sessionTitle(),
            harnessPath = started.harnessFolder,
            workspace = started.workspace,
            privilege = runtime?.environment?.privilege,
            status = when {
                runtime == null -> SessionStatus.CLOSED
                runtime.isRunning(path) -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            },
            createdAtMillis = started.timestampMillis,
            updatedAtMillis = events.sessionUpdatedAtMillis(),
            lastRun = events.lastRunStats(),
            modelSelection = events.modelSelection() ?: spawnPinnedSelection(started),
        )
    }

    private fun spawnPinnedSelection(started: AgentEvent.SessionStarted): ModelSelection? =
        started.parent
            ?.let { parentId -> storedSpawn(parentId, started.sessionId) }
            ?.let { spawn -> spawn.modelId?.let { ModelSelection(it, spawn.reasoningEffort) } }

    private fun storedSpawn(parentId: String, childId: String): AgentEvent.SubagentSpawned? = try {
        store.readEvents(parentId)
            .filterIsInstance<AgentEvent.SubagentSpawned>()
            .lastOrNull { it.sessionId == childId }
    } catch (_: UnknownSessionException) {
        null
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
        val tree = treeOf(id)
        changes.announcing {
            tree.root.mutex.withLock {
                val runtime = tree.root.runtime
                if (runtime == null) {
                    appendToDormantLog(id, dormantEvent)
                } else {
                    val agent = runtime.agentAt(tree.path) ?: throw UnknownSessionException(id)
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
        val nextSequenceId = store.readEvents(id).last().sequenceId + 1
        val stamped = event(nextSequenceId, System.currentTimeMillis())
        try {
            store.eventLogFor(id).use { it.onEvent(stamped) }
        } catch (failure: IOException) {
            throw EventLogFailedException(failure)
        }
        entry(id).eventSignal.value = stamped.sequenceId
    }

    suspend fun close(id: String) {
        val tree = treeOf(id)
        changes.announcing {
            tree.root.mutex.withLock { tree.root.detachRuntime() }
        }
    }

    suspend fun stopRun(id: String) {
        val tree = treeOf(id)
        tree.root.runtime?.stopRun(tree.path)
    }

    suspend fun delete(id: String) {
        val target = entry(id)
        val root = withContext(Dispatchers.IO) {
            try {
                entry(store.pathTo(id).first())
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                target
            }
        }
        changes.announcing {
            root.mutex.withLock {
                root.detachRuntime()
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
        val tree = treeOf(id)
        return changes.announcing {
            tree.root.mutex.withLock {
                tree.requireNoRunInFlight()
                val lastSurviving = storedEvents(id).lastSurvivorOfCutFrom(id, firstDeletedSequenceId)
                executeRewind(tree.root, rootId = tree.rootId, id = id, lastSurviving = lastSurviving)
            }
        }
    }

    private suspend fun storedEvents(id: String): List<AgentEvent> = withContext(Dispatchers.IO) {
        store.readEvents(id)
    }

    private suspend fun executeRewind(
        root: SessionEntry,
        rootId: String,
        id: String,
        lastSurviving: Long,
    ): List<String> = withContext(NonCancellable + Dispatchers.IO) {
        val plan = rewindPlan(id, lastSurviving, store::readEventsOrNull)
        val runtime = root.runtime
        if (runtime == null) applyPlan(plan) else rewindAttachedTree(root, rootId, runtime, plan)
    }

    private suspend fun rewindAttachedTree(
        root: SessionEntry,
        rootId: String,
        runtime: TreeRuntime,
        plan: RewindPlan,
    ): List<String> {
        root.detachRuntime()
        val harness = store.readSessionStarted(rootId).loadHarness()
        val deleted = applyPlan(plan)
        runCatching { root.runtime = loadTreeRuntime(root, rootId, harness, runtime.environment) }
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
        val tree = treeOf(id)
        tree.root.mutex.withLock {
            launchRunLocked(tree) { agent -> agent.send(prompt, settings) }
        }
    }

    suspend fun retryRun(id: String) {
        val tree = treeOf(id)
        changes.announcing {
            tree.root.mutex.withLock {
                tree.requireNoRunInFlight()
                val plan = retryPlan(storedEvents(id))
                executeRewind(tree.root, rootId = tree.rootId, id = id, lastSurviving = plan.lastSurvivingSequenceId)
                launchRunLocked(tree) { agent -> agent.retry(plan.settings) }
            }
        }
    }

    private suspend fun launchRunLocked(tree: SessionTree, run: suspend (Agent) -> RunResult) {
        val root = tree.root
        val attached = root.runtime
        val runtime = attached ?: rebuildTreeRuntime(root, tree.rootId).also { root.runtime = it }
        try {
            val agent = runtime.agentAt(tree.path) ?: throw UnknownSessionException(tree.id)
            runtime.launchRun(agent, run)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            if (attached == null) {
                root.detachRuntime()
            }
            throw failure
        }
    }

    fun eventsAfter(id: String, afterSequenceId: Long): Flow<StoredEvent> {
        val entry = entry(id)
        return store.tailEvents(id, entry.eventSignal, entry.truncations, afterSequenceId)
    }

    suspend fun requireInWorkspace(id: String, workspace: String): Unit = withContext(Dispatchers.IO) {
        requireKnown(id)
        val started = try {
            store.readSessionStarted(id)
        } catch (_: CorruptSessionException) {
            throw UnknownSessionException(id)
        }
        if (normalizedWorkspace(started.workspace) != normalizedWorkspace(workspace)) {
            throw UnknownSessionException(id)
        }
    }

    override fun close() {
        runBlocking {
            ids.forEach { id ->
                runCatching { close(id) }
            }
        }
    }
}

internal class SessionEntry {

    val mutex: Mutex = Mutex()

    val eventSignal: MutableStateFlow<Long> = MutableStateFlow(BEFORE_FIRST_EVENT)

    val truncations: MutableStateFlow<Long> = MutableStateFlow(0L)

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
