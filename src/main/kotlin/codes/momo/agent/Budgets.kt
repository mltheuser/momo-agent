package codes.momo.agent

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Library-wide execution budgets. Exceeding a budget ends the
 * run as failed (enforcement is owned by the agent loop and the tool layer).
 */
public object Budgets {

    /** Maximum number of turns per prompt (a turn = one LLM call). */
    public const val MAX_TURNS: Int = 40

    /** Maximum wall-clock time per prompt. */
    public val MAX_WALL_CLOCK: Duration = 30.minutes

    /** Timeout for a single tool execution. */
    public val TOOL_TIMEOUT: Duration = 5.minutes
}
