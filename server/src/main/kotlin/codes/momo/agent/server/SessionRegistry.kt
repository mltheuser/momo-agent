package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Thrown by every operation naming a session the registry does not know. */
internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

/** Thrown when an operation does not fit the session's current state, e.g. a send racing an active one. */
internal class SessionConflictException(cause: IllegalStateException) : RuntimeException(cause.message, cause)

/**
 * All sessions the server knows, live or dormant. A session *is* its stored
 * log and metadata (see [SessionStore]); a running [Agent] plus its
 * environment are an ephemeral [SessionRuntime] attached to the entry,
 * dropped on close and rebuilt on demand by [attach]. Startup indexes the
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
            val agent = closingOnFailure(environment) {
                Agent(harness, client, environment, title ?: path.fileName.toString(), eventLog)
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
            val entry = SessionEntry()
            entry.runtime = SessionRuntime(agent, environment, eventLog)
            entries[agent.sessionId] = entry
            infoNow(agent.sessionId)
        }

    suspend fun list(): List<SessionInfo> = withContext(Dispatchers.IO) {
        entries.keys.mapNotNull { id ->
            try {
                infoNow(id)
            } catch (_: UnknownSessionException) {
                null // Deleted while listing.
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null // Unreadable stored state must not hide the healthy sessions; get(id) reports it.
            }
        }.sortedBy { it.createdAtMillis }
    }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) { infoNow(id) }

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
                runtime.abortSends()
                runtime.eventLog.close()
            } finally {
                runtime.environment.close()
            }
        }
    }

    /**
     * The live runtime of [id], reattaching a dormant session: harness
     * reloaded, environment rebuilt from the stored spec, agent restored
     * from the stored log. The seam every operation needing the agent goes
     * through.
     */
    suspend fun attach(id: String): SessionRuntime {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        return entry.mutex.withLock {
            entry.runtime ?: withContext(Dispatchers.IO) {
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
                    Agent.load(events, harness, client, environment, eventLog)
                }
                SessionRuntime(agent, environment, eventLog).also { entry.runtime = it }
            }
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

    private fun infoNow(id: String): SessionInfo {
        val entry = entries[id] ?: throw UnknownSessionException(id)
        val metadata: SessionMetadata
        val events: List<AgentEvent>
        try {
            metadata = store.readMetadata(id)
            events = store.readEvents(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted between lookup and read.
        }
        return SessionInfo(
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
}

private fun SessionEntry.status(): SessionStatus {
    val runtime = runtime ?: return SessionStatus.CLOSED
    return if (runtime.sendInFlight) SessionStatus.RUNNING else SessionStatus.IDLE
}

/** Cleanup-and-rethrow: a failed construction must not leak [environment]. */
private inline fun <T> closingOnFailure(environment: ExecutionEnvironment, block: () -> T): T = try {
    block()
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    runCatching { environment.close() }
    throw failure
}

/** One registry slot; [mutex] guards the lifecycle transitions of [runtime]. */
private class SessionEntry {

    val mutex = Mutex()

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

    val sendInFlight: Boolean
        get() = job.children.any { it.isActive }

    /**
     * The one way to drive the agent: the send runs as a child of the
     * runtime, so [abortSends] reaches it.
     *
     * @throws SessionConflictException when a send is already active.
     * @throws IllegalArgumentException from [Agent.send] — passes through unwrapped.
     */
    suspend fun send(text: String): RunResult = try {
        scope.async { agent.send(text) }.await()
    } catch (cancellation: CancellationException) {
        throw cancellation // Extends IllegalStateException; an abort is not a conflict.
    } catch (failure: IllegalStateException) {
        throw SessionConflictException(failure)
    }

    /** Cancels an in-flight [send] and waits for its abort to complete; the runtime takes no further sends. */
    suspend fun abortSends() {
        job.cancelAndJoin()
    }
}
