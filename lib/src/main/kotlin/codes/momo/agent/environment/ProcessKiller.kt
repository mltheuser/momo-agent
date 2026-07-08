package codes.momo.agent.environment

/**
 * How [runProcess] kills its process when the timeout elapses or the calling
 * coroutine is cancelled. Runs in a non-cancellable context; it must leave
 * the process's pipes closing (so the stream drains finish) and must never
 * hang.
 */
internal fun interface ProcessKiller {
    suspend fun kill(process: Process)
}

/**
 * Best-effort host tree kill: descendants are enumerated via
 * [ProcessHandle.descendants] before the parent is destroyed — a dead parent
 * no longer knows its children — because the JVM cannot create POSIX process
 * groups.
 */
internal val HOST_PROCESS_TREE_KILLER: ProcessKiller = ProcessKiller { process ->
    val descendants = process.toHandle().descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}
