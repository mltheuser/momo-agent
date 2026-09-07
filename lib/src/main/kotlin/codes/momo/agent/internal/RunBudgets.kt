package codes.momo.agent.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal object RunBudgets {

    const val MAX_TURNS: Int = 256

    val MAX_WALL_CLOCK: Duration = 3.days

    const val MAX_SUBAGENT_DEPTH: Int = 5
}
