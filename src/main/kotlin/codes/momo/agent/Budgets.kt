package codes.momo.agent

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Library-wide execution budgets, enforced by the agent loop and the tool
 * layer at input boundaries: a tool dispatch is bounded by the smaller of
 * [TOOL_TIMEOUT] and the prompt's remaining wall clock, an LLM call starts
 * only while wall clock remains — the wall clock never cancels work
 * mid-result.
 */
public object Budgets {

    /** Maximum number of turns per prompt (a turn = one LLM call). */
    public const val MAX_TURNS: Int = 40

    /** Maximum active wall-clock time per prompt; time parked awaiting the user is free and unbounded. */
    public val MAX_WALL_CLOCK: Duration = 30.minutes

    /** Timeout for a single tool execution. */
    public val TOOL_TIMEOUT: Duration = 5.minutes
}

/** A specific loop's budget values. */
internal data class RunBudgets(
    val maxTurns: Int = Budgets.MAX_TURNS,
    val maxWallClock: Duration = Budgets.MAX_WALL_CLOCK,
)
