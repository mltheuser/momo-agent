package codes.momo.agent.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class ChangeSignal {

    // Announced from `finally` blocks, so it must never suspend; every signal says the same thing, so dropping is safe.
    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<Unit> = changes.asSharedFlow()

    fun announce() {
        changes.tryEmit(Unit)
    }

    suspend fun <T> announcing(block: suspend () -> T): T = try {
        block()
    } finally {
        announce()
    }
}
