package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.environment.Privilege
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
