package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.loadedSubagentBySessionId
import codes.momo.agent.server.storage.EventLogWriter
import codes.momo.agent.server.storage.readEventsOrNull
import codes.momo.agent.subagentBySessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class ActiveRun(
    val path: List<String>,
    val agent: Agent,
    private val logs: ConcurrentHashMap<String, EventLogWriter>,
) {

    private val job = Job()

    fun launch(block: suspend () -> Unit) {
        CoroutineScope(job + Dispatchers.Default).launch { block() }
    }

    suspend fun abort() {
        withContext(NonCancellable) { job.cancelAndJoin() }
    }

    suspend fun isRunning(path: List<String>): Boolean = path == this.path || loadedAgentAt(path)?.isRunning == true

    suspend fun agentAt(path: List<String>): Agent? =
        descendantIds(path)?.fold(agent as Agent?) { current, childId -> current?.subagentBySessionId(childId) }

    suspend fun loadedAgentAt(path: List<String>): Agent? =
        descendantIds(path)?.fold(agent as Agent?) { current, childId -> current?.loadedSubagentBySessionId(childId) }

    fun closeLogs() {
        logs.values.map { runCatching { it.close() } }
            .firstNotNullOfOrNull { it.exceptionOrNull() }
            ?.let { throw it }
    }

    private fun descendantIds(path: List<String>): List<String>? =
        path.drop(this.path.size).takeIf { path.take(this.path.size) == this.path }
}

internal class TreeMemberListener(
    private val registry: SessionRegistry,
    private val logs: ConcurrentHashMap<String, EventLogWriter>,
    private val entry: SessionEntry,
    private val log: EventLogWriter,
) : AgentEventListener {

    override fun onEvent(event: AgentEvent) {
        log.onEvent(event)
        entry.log.appended(event.sequenceId)
    }

    override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener = TreeMemberListener(
        registry,
        logs,
        registry.entryFor(sessionId),
        logs.computeIfAbsent(sessionId) { registry.store.writer(sessionId) },
    )

    override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = withContext(Dispatchers.IO) {
        registry.store.readEventsOrNull(sessionId)
    }
}

internal fun SessionRegistry.loadRun(tree: SessionTree): ActiveRun {
    val started = store.readSessionStarted(tree.id)
    val harness = Harness.load(Path.of(started.harnessFolder))
    val environment = ExecutionEnvironment(Path.of(started.workspace))
    val logs = ConcurrentHashMap<String, EventLogWriter>()
    val log = store.writer(tree.id).also { logs[tree.id] = it }
    val listener = TreeMemberListener(this, logs, entry(tree.id), log)
    return ActiveRun(tree.path, Agent.load(store.readEvents(tree.id), harness, client, environment, listener), logs)
}
