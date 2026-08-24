package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.environment.Privilege
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * One session as the inspection endpoints report it — derived, never
 * stored. A subagent session's [environment] is its root's; its
 * [harnessPath] names the harness folder the child itself runs (resolved by
 * [SessionRegistry.resolvedHarnessPath]).
 */
@Serializable
internal data class SessionInfo(
    val id: String,
    /** Session ID of the immediate parent; null for a root session. */
    val parent: String?,
    val title: String,
    val harnessPath: String,
    val environment: EnvironmentSpec,
    /**
     * Rights the session's commands run with, as its built environment
     * reports them — discovered, never requested. Null for a `closed`
     * session: with no environment built there is nothing to have asked,
     * and the answer could differ by the time one is.
     */
    val privilege: Privilege?,
    val status: SessionStatus,
    val createdAtMillis: Long,
    /** The last logged event's timestamp — recency for client-side ordering. */
    val updatedAtMillis: Long,
    /**
     * Consumption of the current (or last) run; null whenever the log holds
     * no run — before the first one, and again after a rewind whose cut took
     * every run the log had.
     */
    val lastRun: RunStats?,
    /**
     * The model a client's picker shows for the session's next prompt —
     * derived, never stored: the latest of the log's own `model_selected`
     * picks and `run_started` records (see [modelSelection]), falling back
     * for a child to the parent's spawn pin, and null with neither — the
     * client's own default then stands.
     */
    val modelSelection: ModelSelection?,
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

/** One model selection as [SessionInfo] reports it. */
@Serializable
internal data class ModelSelection(
    val model: String,
    /** Null asks for the provider default. */
    val reasoningEffort: ReasoningEffort? = null,
)

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
 * The selection the log's own events carry, or null when it carries none:
 * the latest by sequence ID of the `model_selected` picks and the
 * `run_started`/`run_resumed` records naming a model — a run updates the
 * shown selection to what actually ran, and a later explicit pick
 * overrides it.
 */
internal fun List<AgentEvent>.modelSelection(): ModelSelection? =
    asReversed().firstNotNullOfOrNull { event ->
        when (event) {
            is AgentEvent.ModelSelected -> ModelSelection(event.model, event.reasoningEffort)
            is AgentEvent.RunStarted -> event.model?.let { ModelSelection(it, event.reasoningEffort) }
            is AgentEvent.RunResumed -> ModelSelection(event.model, event.reasoningEffort)
            else -> null
        }
    }

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
