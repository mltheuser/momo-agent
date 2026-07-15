package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * One session as the inspection endpoints report it — derived, never
 * stored. A subagent session's [model], [harnessPath], [environment], and
 * [favorite] are its root's.
 */
@Serializable
internal data class SessionInfo(
    val id: String,
    /** Session ID of the immediate parent; null for a root session. */
    val parent: String?,
    val title: String,
    val model: String,
    val harnessPath: String,
    val environment: EnvironmentSpec,
    val status: SessionStatus,
    val favorite: Boolean,
    val createdAtMillis: Long,
    /** The last logged event's timestamp — recency for client-side ordering. */
    val updatedAtMillis: Long,
    /** Consumption of the current (or last) run; null before the first run. */
    val lastRun: RunStats?,
)

@Serializable
internal enum class SessionStatus {

    /** A run is in flight. */
    @SerialName("running")
    RUNNING,

    /** Live with nothing running. */
    @SerialName("idle")
    IDLE,

    /** Dormant — no runtime attached; resumable by ID. */
    @SerialName("closed")
    CLOSED,
}

/** Budget consumption of one run. */
@Serializable
internal data class RunStats(
    val turnsUsed: Int,
    val totalTokens: Int,
    /** Wall-clock the run consumed, per its latest logged accounting. */
    val elapsed: Duration,
)

// ─── Read models over a stored event log ──────────────────────────────

internal fun List<AgentEvent>.sessionCreatedAtMillis(): Long =
    (first() as AgentEvent.SessionStarted).timestampMillis

internal fun List<AgentEvent>.sessionUpdatedAtMillis(): Long = last().timestampMillis

internal fun List<AgentEvent>.sessionTitle(): String =
    filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title
        ?: (first() as AgentEvent.SessionStarted).title

/**
 * Consumption of the log's last run: its `RunFinished` totals once it
 * ended, otherwise rebuilt from the turns logged so far.
 */
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
