package codes.momo.agent.server.session

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.environment.Privilege
import codes.momo.agent.server.storage.CorruptSessionException
import codes.momo.agent.server.storage.UnknownSessionException
import codes.momo.agent.server.storage.pathTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration

@Serializable
internal data class SessionInfo(
    val id: String,

    val parent: String?,
    val title: String,
    val harnessPath: String,
    val workspace: String,

    val privilege: Privilege?,
    val status: SessionStatus,
    val createdAtMillis: Long,

    val updatedAtMillis: Long,

    val lastRun: RunStats?,

    val modelSelection: ModelSelection?,
)

@Serializable
internal enum class SessionStatus {

    @SerialName("running")
    RUNNING,

    @SerialName("idle")
    IDLE,

    @SerialName("closed")
    CLOSED,
}

@Serializable
internal data class ModelSelection(
    val model: String,

    val reasoningEffort: ReasoningEffort? = null,
)

@Serializable
internal data class RunStats(
    val turnsUsed: Int,
    val totalTokens: Int,

    val elapsed: Duration,
)

internal suspend fun SessionRegistry.list(workspace: String): List<SessionInfo> {
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

internal suspend fun SessionRegistry.info(id: String): SessionInfo = withContext(Dispatchers.IO) {
    requireKnown(id)
    val path = store.pathTo(id)
    val events = store.readEvents(id)
    val started = events.sessionStarted()
    val runtime = entryOrNull(path.first())?.runtime
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

private fun SessionRegistry.spawnPinnedSelection(started: AgentEvent.SessionStarted): ModelSelection? =
    started.parent
        ?.let { parentId -> storedSpawn(parentId, started.sessionId) }
        ?.let { spawn -> spawn.modelId?.let { ModelSelection(it, spawn.reasoningEffort) } }

private fun SessionRegistry.storedSpawn(parentId: String, childId: String): AgentEvent.SubagentSpawned? = try {
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

internal fun normalizedWorkspace(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

internal fun List<AgentEvent>.sessionStarted(): AgentEvent.SessionStarted = first() as AgentEvent.SessionStarted

internal val AgentEvent.SessionStarted.harnessFolder: String
    get() = checkNotNull(harnessPath) { "Session $sessionId runs a harness without a folder." }

internal fun List<AgentEvent>.sessionUpdatedAtMillis(): Long = last().timestampMillis

internal fun List<AgentEvent>.sessionTitle(): String =
    filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title ?: sessionStarted().title

internal fun List<AgentEvent>.modelSelection(): ModelSelection? =
    asReversed().firstNotNullOfOrNull { event ->
        when (event) {
            is AgentEvent.ModelSelected -> ModelSelection(event.model, event.reasoningEffort)
            is AgentEvent.RunStarted -> event.model?.let { ModelSelection(it, event.reasoningEffort) }
            is AgentEvent.RunResumed -> ModelSelection(event.model, event.reasoningEffort)
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
