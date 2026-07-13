package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown by every operation naming a session the registry does not know. */
internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

/** Thrown when an operation does not fit the session's current state, e.g. a prompt racing an active run. */
internal class SessionConflictException(message: String) : RuntimeException(message)

/** Thrown when a session's event log stopped persisting. */
internal class EventLogFailedException(cause: IOException) :
    RuntimeException("The session's event log failed: ${cause.message}", cause)

/**
 * All sessions the server knows, live or dormant. A session *is* its stored
 * log and metadata (see [SessionStore]); a running [Agent] plus its
 * environment are an ephemeral [SessionRuntime] attached to the entry,
 * dropped on close and rebuilt on demand when a prompt arrives (see
 * [startRun]). Startup indexes the
 * data directory, so sessions stored by an earlier process appear as
 * dormant entries — a restart is an implicit close of everything that was
 * live.
 *
 * Lifecycle transitions serialize per session, never across sessions;
 * blocking work (harness and store IO, environment construction — possibly
 * a slow image pull) runs on the IO dispatcher.
 */
internal class SessionRegistry(
    dataDir: Path,
    private val client: AiRouterClient,
) : AutoCloseable {

    private val store = SessionStore(dataDir)

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    init {
        store.sessionIds().forEach { entries[it] = SessionEntry() }
    }

    /**
     * Creates a live session from the harness folder at [harnessPath] and a
     * fresh environment built from [spec], returning its first [SessionInfo].
     * [title] defaults to the harness folder's name.
     *
     * @throws codes.momo.agent.harness.HarnessValidationException when the
     *   harness folder is invalid.
     * @throws codes.momo.agent.environment.EnvironmentStartupException when
     *   the environment cannot be built.
     */
    suspend fun create(harnessPath: String, spec: EnvironmentSpec, title: String? = null): SessionInfo =
        withContext(Dispatchers.IO) {
            val path = Path.of(harnessPath)
            val harness = Harness.load(path)
            val eventLog = store.eventLogForNewSession()
            val environment = spec.build()
            val entry = SessionEntry()
            val agent = closingOnFailure(environment) {
                Agent(harness, client, environment, title ?: path.fileName.toString(), entry.signaling(eventLog))
            }
            try {
                store.writeMetadata(agent.sessionId, SessionMetadata(harnessPath, harness.model, spec))
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Without metadata the session can never be rebuilt: discard
                // every artifact instead of leaking the live environment.
                runCatching { eventLog.close() }
                runCatching { environment.close() }
                runCatching { store.delete(agent.sessionId) }
                throw failure
            }
            entry.runtime = SessionRuntime(agent, environment, eventLog)
            entries[agent.sessionId] = entry
            info(agent.sessionId)
        }

    suspend fun list(): List<SessionInfo> = entries.keys.mapNotNull { id ->
        try {
            info(id)
        } catch (_: UnknownSessionException) {
            null // Deleted while listing.
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            null // Unreadable stored state must not hide the healthy sessions; get(id) reports it.
        }
    }.sortedBy { it.createdAtMillis }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        val metadata: SessionMetadata
        val events: List<AgentEvent>
        try {
            metadata = store.readMetadata(id)
            events = store.readEvents(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted between lookup and read.
        }
        SessionInfo(
            id = id,
            title = events.sessionTitle(),
            model = metadata.model,
            harnessPath = metadata.harnessPath,
            environment = metadata.environment,
            status = entry.status(),
            createdAtMillis = events.sessionCreatedAtMillis(),
            lastRun = events.lastRunStats(),
        )
    }

    /**
     * Closes [id]: cancels in-flight work, closes the environment (a
     * container copies its workspace back to the host), and drops the
     * runtime attachment. The stored session stays listed and resumable.
     * Closing a dormant session is a no-op.
     *
     * @throws codes.momo.agent.environment.EnvironmentFailureException when
     *   the environment's teardown failed; the session is closed regardless.
     */
    suspend fun close(id: String) {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        entry.mutex.withLock { teardown(entry) }
    }

    /** Closes [id] if live, then removes it: the registry entry and every stored artifact. */
    suspend fun delete(id: String) {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        entry.mutex.withLock {
            teardown(entry)
            entries.remove(id)
            withContext(NonCancellable + Dispatchers.IO) { store.delete(id) }
            entry.eventSignal.value = SESSION_DELETED_SIGNAL
        }
    }

    /**
     * Starts a run over [prompt] on [id], reattaching a dormant session.
     * Attach and start happen under the entry's mutex, so a concurrent
     * close cannot void an accepted prompt between them.
     *
     * @throws EventLogFailedException when the session's log stopped
     *   persisting: without it a run would leave no record.
     * @throws SessionConflictException when a run is already active.
     */
    suspend fun startRun(id: String, prompt: String) {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        entry.mutex.withLock {
            val runtime = entry.runtime ?: rebuild(entry, id).also { entry.runtime = it }
            runtime.startRun(prompt)
        }
    }

    /**
     * Stream of [id]'s stored events via [SessionStore.tailEvents];
     * subscribing never attaches a dormant session.
     */
    fun eventsAfter(id: String, afterSequenceId: Long): Flow<StoredEvent> {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        return store.tailEvents(id, entry.eventSignal, afterSequenceId)
    }

    /** @throws UnknownSessionException when [id] names no known session. */
    fun requireKnown(id: String) {
        if (!entries.containsKey(id)) {
            throw UnknownSessionException(id)
        }
    }

    /** Closes every live session; the stored sessions stay for the next process. */
    override fun close() {
        runBlocking {
            entries.keys.forEach { id ->
                runCatching { close(id) }
            }
        }
    }

    /** Rebuilds [id]'s runtime from its stored artifacts; the caller holds the entry's mutex. */
    private suspend fun rebuild(entry: SessionEntry, id: String): SessionRuntime = withContext(Dispatchers.IO) {
        val metadata = try {
            store.readMetadata(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
        val harness = Harness.load(Path.of(metadata.harnessPath))
        val events = store.readEvents(id)
        val eventLog = store.eventLogFor(id)
        val environment = metadata.environment.build()
        val agent = closingOnFailure(environment) {
            Agent.load(events, harness, client, environment, entry.signaling(eventLog))
        }
        SessionRuntime(agent, environment, eventLog)
    }
}

/** Tears down [entry]'s runtime if live; the caller holds the entry's mutex. */
private suspend fun teardown(entry: SessionEntry) {
    val runtime = entry.runtime ?: return
    entry.runtime = null
    // Shielded: a caller's cancellation (a client disconnecting
    // mid-request) must not abandon a live environment.
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            runtime.abortRuns()
            runtime.eventLog.close()
        } finally {
            runtime.environment.close()
        }
    }
}

private fun SessionEntry.status(): SessionStatus {
    val runtime = runtime ?: return SessionStatus.CLOSED
    return if (runtime.runInFlight) SessionStatus.RUNNING else SessionStatus.IDLE
}

/** The runtime's listener: the log first — the event is on disk when the signal fires — then the wake-up. */
private fun SessionEntry.signaling(log: PersistedEventLog): AgentEventListener = AgentEventListener { event ->
    log.onEvent(event)
    eventSignal.value = event.sequenceId
}

/** Cleanup-and-rethrow: a failed construction must not leak [environment]. */
private inline fun <T> closingOnFailure(environment: ExecutionEnvironment, block: () -> T): T = try {
    block()
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    runCatching { environment.close() }
    throw failure
}

/**
 * One registry slot; [mutex] guards the lifecycle transitions of [runtime].
 * [eventSignal] carries the latest logged sequenceId as the wake-up event
 * tails wait on; it outlives the runtime so subscribers of a dormant
 * session see the events of a later reattachment.
 */
private class SessionEntry {

    val mutex = Mutex()

    val eventSignal = MutableStateFlow(BEFORE_FIRST_EVENT)

    @Volatile
    var runtime: SessionRuntime? = null
}

/** The ephemeral runtime attachment of a live session. */
internal class SessionRuntime(
    private val agent: Agent,
    val environment: ExecutionEnvironment,
    val eventLog: PersistedEventLog,
) {

    private val job = SupervisorJob()

    private val scope = CoroutineScope(job + Dispatchers.Default)

    private val runActive = AtomicBoolean(false)

    val runInFlight: Boolean
        get() = runActive.get()

    /**
     * The one way to drive the agent: starts a run over [prompt] as a child
     * of the runtime — so [abortRuns] reaches it — and returns as soon as
     * it is started. The run's outcome is never returned; its
     * [AgentEvent.RunFinished] log entry is the record.
     */
    fun startRun(prompt: String) {
        eventLog.failure?.let { throw EventLogFailedException(it) }
        if (!runActive.compareAndSet(false, true)) {
            throw SessionConflictException("A run is already active on this session.")
        }
        scope.launch { agent.send(prompt) }.invokeOnCompletion { runActive.set(false) }
    }

    /** Cancels an in-flight run and waits for its abort to complete; the runtime takes no further runs. */
    suspend fun abortRuns() {
        job.cancelAndJoin()
    }
}
