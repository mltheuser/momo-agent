package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/** One session as the inspection endpoints report it — derived, never stored. */
@Serializable
internal data class SessionInfo(
    val id: String,
    val title: String,
    val model: String,
    val harnessPath: String,
    val environment: EnvironmentSpec,
    val status: SessionStatus,
    val createdAtMillis: Long,
    /** Consumption of the current (or last) prompt; null before the first prompt. */
    val lastPrompt: PromptStats?,
    /** The question a parked session awaits; null when none is pending. */
    val pendingQuestion: String?,
)

@Serializable
internal enum class SessionStatus {

    /** A send is in flight. */
    @SerialName("running")
    RUNNING,

    /** Live and parked on a question. */
    @SerialName("awaiting_user")
    AWAITING_USER,

    /** Live with nothing running. */
    @SerialName("idle")
    IDLE,

    /** Dormant — no runtime attached; resumable by ID. */
    @SerialName("closed")
    CLOSED,
}

/** Budget consumption of one prompt. */
@Serializable
internal data class PromptStats(
    val turnsUsed: Int,
    val totalTokens: Int,
    /** Active wall-clock the prompt consumed, per its latest logged accounting. */
    val elapsed: Duration,
)

// ─── Read models over a stored event log ──────────────────────────────

internal fun List<AgentEvent>.sessionCreatedAtMillis(): Long =
    (first() as AgentEvent.SessionStarted).timestampMillis

internal fun List<AgentEvent>.sessionTitle(): String =
    filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title
        ?: (first() as AgentEvent.SessionStarted).title

/**
 * Consumption of the log's last prompt: its `RunFinished` totals once it
 * ended, otherwise rebuilt from the turns logged so far.
 */
internal fun List<AgentEvent>.lastPromptStats(): PromptStats? {
    if (none { it is AgentEvent.RunStarted }) {
        return null
    }
    val run = takeLastWhile { it !is AgentEvent.RunStarted }
    val finished = run.filterIsInstance<AgentEvent.RunFinished>().lastOrNull()
    return if (finished != null) {
        PromptStats(finished.turnsUsed, finished.usage.totalTokens, finished.elapsed)
    } else {
        val turns = run.filterIsInstance<AgentEvent.LlmCallFinished>()
        PromptStats(
            turnsUsed = turns.size,
            totalTokens = turns.sumOf { it.usage.totalTokens },
            elapsed = run.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
        )
    }
}
