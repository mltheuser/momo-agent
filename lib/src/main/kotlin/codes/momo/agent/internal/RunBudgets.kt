package codes.momo.agent.internal

import codes.momo.agent.tool.TOOL_TIMEOUT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal data class RunBudgets(
    val maxTurns: Int = MAX_TURNS,
    val maxWallClock: Duration = MAX_WALL_CLOCK,
    val toolTimeout: Duration = TOOL_TIMEOUT,
    val retryBackoffs: List<Duration> = RETRY_BACKOFFS,
) {

    companion object {

        const val MAX_TURNS: Int = 256

        val MAX_WALL_CLOCK: Duration = 3.days

        const val MAX_SUBAGENT_DEPTH: Int = 5
    }
}
