package codes.momo.agent.server.session

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunSettings
import codes.momo.agent.server.storage.ifReadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.time.Duration

@Serializable
internal data class SessionInfo(
    val id: String,

    val parent: String?,
    val title: String,
    val harnessPath: String,
    val workspace: String,

    val status: SessionStatus,
    val createdAtMillis: Long,

    val updatedAtMillis: Long,

    val lastRun: RunStats?,

    val modelSelection: RunSettings?,
)

@Serializable
internal enum class SessionStatus {

    @SerialName("running")
    RUNNING,

    @SerialName("idle")
    IDLE,
}

@Serializable
internal data class RunStats(
    val turnsUsed: Int,
    val totalTokens: Int,

    val elapsed: Duration,
)

internal suspend fun SessionRegistry.list(workspace: String): List<SessionInfo> = withContext(Dispatchers.IO) {
    val scope = normalizedWorkspace(workspace)
    ids.mapNotNull { id ->
        store.ifReadable {
            readSessionStarted(id)
                .takeIf { it.parent == null && normalizedWorkspace(it.workspace) == scope }
                ?.let { info(id) }
        }
    }.sortedBy { it.createdAtMillis }
}

internal suspend fun SessionRegistry.info(id: String): SessionInfo {
    val tree = settledTreeOf(id)
    return withContext(Dispatchers.IO) {
        val events = store.readEvents(id)
        val started = events.sessionStarted()
        SessionInfo(
            id = id,
            parent = started.parent,
            title = events.sessionTitle(),
            harnessPath = started.harnessFolder,
            workspace = started.workspace,
            status = if (tree.root.run?.isRunning(tree.path) == true) SessionStatus.RUNNING else SessionStatus.IDLE,
            createdAtMillis = started.timestampMillis,
            updatedAtMillis = events.sessionUpdatedAtMillis(),
            lastRun = events.lastRunStats(),
            modelSelection = events.modelSelection() ?: inheritedSelection(started),
        )
    }
}

// A child that has chosen nothing itself runs with its parent's settings under its spawn pins.
private fun SessionRegistry.inheritedSelection(started: AgentEvent.SessionStarted): RunSettings? {
    val parentEvents = started.parent?.let { store.ifReadable { readEvents(it) } } ?: return null
    val spawn = parentEvents.filterIsInstance<AgentEvent.SubagentSpawned>().last { it.sessionId == started.sessionId }
    return parentEvents.modelSelection()?.pinnedBy(spawn.modelId, spawn.reasoningEffort)
}

internal fun normalizedWorkspace(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

internal fun List<AgentEvent>.sessionStarted(): AgentEvent.SessionStarted = first() as AgentEvent.SessionStarted

internal val AgentEvent.SessionStarted.harnessFolder: String
    get() = checkNotNull(harnessPath) { "Session $sessionId runs a harness without a folder." }

internal fun List<AgentEvent>.sessionUpdatedAtMillis(): Long = last().timestampMillis

internal fun List<AgentEvent>.sessionTitle(): String =
    filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title ?: sessionStarted().title

internal fun List<AgentEvent>.modelSelection(): RunSettings? =
    asReversed().firstNotNullOfOrNull { event ->
        when (event) {
            is AgentEvent.ModelSelected -> event.settings
            is AgentEvent.RunStarted -> event.settings
            else -> null
        }
    }

internal fun List<AgentEvent>.lastRunStats(): RunStats? {
    if (none { it is AgentEvent.RunStarted }) {
        return null
    }
    val run = takeLastWhile { it !is AgentEvent.RunStarted }
    val finished = run.filterIsInstance<AgentEvent.RunFinished>().lastOrNull()
    return if (finished != null) {
        RunStats(finished.turnsUsed, finished.usage.totalTokens, finished.elapsed)
    } else {
        val turns = run.filterIsInstance<AgentEvent.LlmCallFinished>()
        RunStats(
            turnsUsed = turns.size,
            totalTokens = turns.sumOf { it.usage.totalTokens },
            elapsed = run.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
        )
    }
}
