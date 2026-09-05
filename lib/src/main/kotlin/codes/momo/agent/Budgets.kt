package codes.momo.agent

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

public object Budgets {

    public const val MAX_TURNS: Int = 256

    public val MAX_WALL_CLOCK: Duration = 3.days

    public val TOOL_TIMEOUT: Duration = 24.hours

    public const val MAX_SUBAGENT_DEPTH: Int = 5
}

internal data class RunBudgets(
    val maxTurns: Int = Budgets.MAX_TURNS,
    val maxWallClock: Duration = Budgets.MAX_WALL_CLOCK,
    val toolTimeout: Duration = Budgets.TOOL_TIMEOUT,

    val retryBackoffs: List<Duration> = RETRY_BACKOFFS,
)
