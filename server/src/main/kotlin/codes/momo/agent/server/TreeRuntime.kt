package codes.momo.agent.server

import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.RunResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.liveSubagentBySessionId
import codes.momo.agent.subagentBySessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class TreeRuntime(
    private val rootAgent: Agent,
    val environment: ExecutionEnvironment,
    private val logs: ConcurrentHashMap<String, PersistedEventLog>,
    private val changes: ChangeSignal,
) {

    private val job = SupervisorJob()

    private val scope = CoroutineScope(job + Dispatchers.Default)

    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    val rootId: String
        get() = rootAgent.sessionId

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

        changes.announce()
        scope.launch {
            try {
                run(agent)
            } finally {
                activeRuns.remove(agent.sessionId)
                changes.announce()
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

private class TreeMemberListener(
    private val registry: SessionRegistry,
    private val logs: ConcurrentHashMap<String, PersistedEventLog>,
    private val entry: SessionEntry,
    private val log: PersistedEventLog,
) : AgentEventListener {

    override fun onEvent(event: AgentEvent) {
        log.onEvent(event)
        entry.eventSignal.value = event.sequenceId
    }

    override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener = TreeMemberListener(
        registry,
        logs,
        registry.entryFor(sessionId),
        logs.computeIfAbsent(sessionId) { registry.store.eventLogFor(sessionId) },
    )

    override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = withContext(Dispatchers.IO) {
        registry.store.readEventsOrNull(sessionId)
    }
}

internal fun SessionRegistry.buildTreeRuntime(
    root: SessionEntry,
    environment: ExecutionEnvironment,
    rootLog: PersistedEventLog,
    buildRootAgent: (AgentEventListener) -> Agent,
): TreeRuntime {
    val logs = ConcurrentHashMap<String, PersistedEventLog>()
    val agent = buildRootAgent(TreeMemberListener(this, logs, root, rootLog))
    logs[agent.sessionId] = rootLog
    return TreeRuntime(agent, environment, logs, changes)
}

internal suspend fun SessionRegistry.rebuildTreeRuntime(root: SessionEntry, rootId: String): TreeRuntime =
    withContext(Dispatchers.IO) {
        val started = store.readSessionStarted(rootId)
        loadTreeRuntime(root, rootId, started.loadHarness(), ExecutionEnvironment(Path.of(started.workspace)))
    }

internal fun SessionRegistry.loadTreeRuntime(
    root: SessionEntry,
    rootId: String,
    harness: Harness,
    environment: ExecutionEnvironment,
): TreeRuntime {
    val events = store.readEvents(rootId)
    return buildTreeRuntime(root, environment, store.eventLogFor(rootId)) { listener ->
        Agent.load(events, harness, client, environment, listener)
    }
}

internal fun AgentEvent.SessionStarted.loadHarness(): Harness = Harness.load(Path.of(harnessFolder))

private const val RUN_ACTIVE_MESSAGE = "A run is already active on this session."
