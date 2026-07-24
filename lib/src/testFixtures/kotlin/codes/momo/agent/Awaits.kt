package codes.momo.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Suspends until [marker] appears — the touch of a scripted slow tool
 * call, so the run that issued it is in flight inside that call.
 */
public suspend fun awaitExists(marker: Path) {
    withTimeout(AWAIT_TIMEOUT) {
        while (marker.notExists()) {
            delay(POLL_INTERVAL)
        }
    }
}

/** Suspends until the child collected as [name] has started an LLM call, so its run is under way. */
public suspend fun TreeEventListener.awaitChildLlmCall(name: String) {
    withTimeout(AWAIT_TIMEOUT) {
        while (children[name]?.events.orEmpty().none { it is AgentEvent.LlmCallStarted }) {
            delay(POLL_INTERVAL)
        }
    }
}

private val AWAIT_TIMEOUT = 5.seconds

private val POLL_INTERVAL = 10.milliseconds
