package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.liveSubagentBySessionId
import codes.momo.agent.subagentBySessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Thrown by every operation naming a session the registry does not know. */
internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

/** Thrown when an operation does not fit the session's current state, e.g. a prompt racing an active run. */
internal class SessionConflictException(message: String) : RuntimeException(message)

/** Thrown when a session's event log stopped persisting. */
internal class EventLogFailedException(cause: IOException) :
    RuntimeException("The session's event log failed: ${cause.message}", cause)

/** Thrown when a rewind names a sequence ID its session's log cannot start a cut at. */
internal class InvalidRewindPointException(message: String) : RuntimeException(message)

/**
 * All sessions the server knows, live or dormant. A session *is* its stored
 * log and metadata (see [SessionStore]); the running agents of a subagent
 * tree plus their one shared environment are an ephemeral [TreeRuntime]
 * attached to the tree's root entry — dropped only on explicit close,
 * delete, or shutdown, and rebuilt on demand when a prompt arrives (see
 * [startRun]). Startup indexes the data directory, so sessions stored by
 * an earlier process appear as dormant entries — a restart is an implicit
 * close of everything that was live.
 *
 * Tree lifecycle transitions serialize on the root entry's mutex — the
 * only mutex ever locked, so there is no lock ordering to get wrong;
 * blocking work (harness and store IO, environment construction) runs on
 * the IO dispatcher.
 */
@Suppress("TooManyFunctions") // One cohesive surface over the shared entry map and the root-mutex discipline.
internal class SessionRegistry(
    dataDir: Path,
    private val client: AiRouterClient,
) : AutoCloseable {

    private val store = SessionStore(dataDir)

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    /**
     * Buffered by one and dropping the oldest, so [announceChange] never
     * suspends and never fails: every caller announces from a `finally`,
     * where a cancelled coroutine has no room to suspend at all. Dropping the
     * oldest — never the newest — costs nothing, because every signal says
     * the same thing: a subscriber too slow for a burst still receives the
     * last of it, and that one stands for all the rest.
     */
    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Signalled whenever a listed session's identity or state changes: one
     * created or deleted, a title, a model selection, and a
     * [SessionStatus] — so twice per run,
     * as it starts and as it ends. What an in-flight run keeps moving is
     * deliberately not signalled: a subscriber following
     * [SessionInfo.updatedAtMillis] or [SessionInfo.lastRun] as they climb
     * follows the session's own event stream.
     *
     * A signal is a hint to re-read, never a record of what changed: it is
     * unnumbered, unreplayed and carries no state, and it stands behind the
     * change it reports, so a read taken on its heels already accounts for
     * that change. A missed signal costs nothing but the wait for the next.
     */
    val sessionsChanged: SharedFlow<Unit> = changes.asSharedFlow()

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
        announcingChange {
            withContext(Dispatchers.IO) {
                val path = Path.of(harnessPath)
                val harness = Harness.load(path)
                val eventLog = store.eventLogForNewSession()
                val environment = spec.build()
                val entry = SessionEntry()
                val logs = ConcurrentHashMap<String, PersistedEventLog>()
                val listener = TreeMemberListener(logs, entry, eventLog, sessionId = null)
                val agent = Agent(harness, client, environment, title ?: path.fileName.toString(), listener)
                logs[agent.sessionId] = eventLog
                try {
                    store.writeMetadata(agent.sessionId, SessionMetadata.Root(harnessPath, spec))
                } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                    // Without metadata the session can never be rebuilt: discard every artifact.
                    runCatching { eventLog.close() }
                    runCatching { store.delete(agent.sessionId) }
                    throw failure
                }
                entry.runtime = TreeRuntime(agent, environment, logs, ::announceChange)
                entries[agent.sessionId] = entry
                info(agent.sessionId)
            }
        }

    /**
     * The root sessions whose workspace is [workspace] — children are
     * discovered through their parent's `subagent_spawned` events, and every
     * other workspace's sessions are somebody else's listing.
     *
     * The filter reads the stored metadata and stops there for a session it
     * rejects: [info] parses that session's whole event log, and this listing
     * is re-read on every change frame in every window, so paying that per
     * session on the machine would grow with every project ever touched.
     */
    suspend fun list(workspace: String): List<SessionInfo> {
        val scope = normalizedWorkspace(workspace)
        return entries.keys.mapNotNull { id ->
            try {
                val metadata = withContext(Dispatchers.IO) { store.readMetadata(id) }
                when {
                    metadata !is SessionMetadata.Root -> null
                    normalizedWorkspace(metadata.environment.workspace) != scope -> null
                    else -> info(id)
                }
            } catch (_: UnknownSessionException) {
                null // Deleted while listing.
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null // Unreadable stored state must not hide the healthy sessions; get(id) reports it.
            }
        }.sortedBy { it.createdAtMillis }
    }

    suspend fun info(id: String): SessionInfo = withContext(Dispatchers.IO) {
        entries.known(id)
        val events: List<AgentEvent>
        val position: TreePosition
        try {
            events = store.readEvents(id)
            position = store.position(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted between lookup and read.
        }
        // Status comes from the tree's runtime, never from the log's tail: a
        // run cut by a close leaves no RunFinished behind to read, and a
        // stopped run leaves one that must not read as a closed session.
        val runtime = entries[position.path.first()]?.runtime
        SessionInfo(
            id = id,
            parent = position.path.dropLast(1).lastOrNull(),
            title = events.sessionTitle(),
            harnessPath = resolvedHarnessPath(position),
            environment = position.root.environment,
            privilege = runtime?.environment?.privilege,
            status = when {
                runtime == null -> SessionStatus.CLOSED
                runtime.isRunning(position.path) -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            },
            createdAtMillis = events.sessionCreatedAtMillis(),
            updatedAtMillis = events.sessionUpdatedAtMillis(),
            lastRun = events.lastRunStats(),
            modelSelection = events.modelSelection() ?: spawnPinnedSelection(position),
        )
    }

    /**
     * The [SessionInfo.modelSelection] fallback for a child whose own log
     * names no selection: what its spawn pinned, if anything — visible in
     * the picker before the child has run at all. A selection must name a
     * model, so an effort pinned without one has no fallback to ride.
     */
    private fun spawnPinnedSelection(position: TreePosition): ModelSelection? =
        position.path.dropLast(1).lastOrNull()
            ?.let { parentId -> storedSpawn(parentId, position.path.last()) }
            ?.let { spawn -> spawn.modelId?.let { ModelSelection(it, spawn.reasoningEffort) } }

    /**
     * The folder of the harness [position]'s session actually runs: for a
     * child, the root's loaded harness followed hop by hop through each
     * stored spawn's declared type. Any unresolvable hop — a root harness
     * that no longer loads, an untyped stored spawn, a type the harness no
     * longer declares, an unreadable ancestor log — degrades to the root's
     * stored harness path, so inspection never fails on configuration drift.
     */
    private fun resolvedHarnessPath(position: TreePosition): String {
        if (position.path.size == 1) {
            return position.root.harnessPath
        }
        val resolved = position.path.zipWithNext().fold(rootHarnessOrNull(position.root)) { harness, hop ->
            val (parentId, childId) = hop
            if (harness == null) {
                null
            } else {
                storedSpawn(parentId, childId)?.type?.let { type -> harness.subagents[type]?.harness }
            }
        }
        return resolved?.folder?.toString() ?: position.root.harnessPath
    }

    private fun rootHarnessOrNull(root: SessionMetadata.Root): Harness? = try {
        Harness.load(Path.of(root.harnessPath))
    } catch (_: HarnessValidationException) {
        null
    }

    /** The stored spawn of [childId] in [parentId]'s log; a session ID is spawned at most once. */
    private fun storedSpawn(parentId: String, childId: String): AgentEvent.SubagentSpawned? = try {
        store.readEvents(parentId)
            .filterIsInstance<AgentEvent.SubagentSpawned>()
            .lastOrNull { it.sessionId == childId }
    } catch (_: IOException) {
        null
    } catch (_: CorruptSessionException) {
        null
    }

    /** Sets [id]'s title to [title] — user metadata recorded per [recordMetadata] — returning the updated info. */
    suspend fun rename(id: String, title: String): SessionInfo = recordMetadata(
        id,
        onAgent = { it.title = title },
        dormantEvent = { sequenceId, at -> AgentEvent.SessionRenamed(sequenceId, at, title) },
    )

    /**
     * Records the model a client selected for [id]'s next prompt — an
     * [AgentEvent.ModelSelected] recorded per [recordMetadata] and derived
     * back out as [SessionInfo.modelSelection] — returning the updated info.
     */
    suspend fun selectModel(id: String, model: String, reasoningEffort: ReasoningEffort?): SessionInfo =
        recordMetadata(
            id,
            onAgent = { it.recordModelSelection(model, reasoningEffort) },
            dormantEvent = { sequenceId, at -> AgentEvent.ModelSelected(sequenceId, at, model, reasoningEffort) },
        )

    /**
     * Records one user-metadata event on [id], returning the updated info.
     * A member of an attached tree records through its agent via [onAgent],
     * so sequence numbering stays with the emitter; a dormant session gets
     * [dormantEvent] appended straight to its stored log — recording never
     * attaches a runtime. The root's mutex serializes both paths, so a
     * direct append cannot race a prompt rebuilding the tree.
     */
    private suspend fun recordMetadata(
        id: String,
        onAgent: (Agent) -> Unit,
        dormantEvent: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
    ): SessionInfo {
        val (path, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock {
                val runtime = root.runtime
                if (runtime == null) {
                    appendToDormantLog(id, dormantEvent)
                } else {
                    val agent = runtime.agentAt(path) ?: throw UnknownSessionException(id)
                    withContext(Dispatchers.IO) { onAgent(agent) }
                }
            }
        }
        return info(id)
    }

    /** Appends [event], stamped, to dormant [id]'s stored log; the caller holds the root's mutex. */
    private suspend fun appendToDormantLog(
        id: String,
        event: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
    ) = withContext(Dispatchers.IO) {
        val nextSequenceId = try {
            // The next sequence ID exactly as the lib's restore path —
            // restoredSession in SessionState.kt — derives it; the two
            // must always agree.
            store.readEvents(id).last().sequenceId + 1
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
        val stamped = event(nextSequenceId, System.currentTimeMillis())
        try {
            store.eventLogFor(id).use { it.onEvent(stamped) }
        } catch (failure: IOException) {
            throw EventLogFailedException(failure)
        }
        entries.known(id).eventSignal.value = stamped.sequenceId
    }

    /**
     * Closes [id]'s whole tree: cancels every in-flight run in it, closes
     * the open event logs, and drops the runtime attachment. Every member
     * stays stored and resumable. Closing a dormant tree is a no-op.
     */
    suspend fun close(id: String) {
        val (_, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock { teardown(root) }
        }
    }

    /**
     * Stops [id]'s in-flight run — see [Agent.stop] for what a stop does to
     * the run itself. A run [startRun] has claimed but not yet started is
     * one a stop may always outrun, and finding nothing to stop is a no-op
     * success like any other.
     *
     * The one mutating operation that does not take the root's mutex, since
     * it is no lifecycle transition — it attaches nothing, detaches nothing,
     * writes no metadata, and reads only the attached runtime and its live
     * links. Staying off the mutex is what keeps a stop from queueing behind
     * an unrelated tree member's transition; a tree still being rebuilt has
     * no runtime to reach either way. Racing a close is benign, as its
     * teardown cancels the same runs.
     */
    suspend fun stopRun(id: String) {
        val (path, root) = treeOf(entries, store, id)
        root.runtime?.stopRun(path)
    }

    /**
     * Closes [id]'s whole tree, then removes [id]'s subtree: the session
     * itself and every stored descendant lose their registry entries and
     * stored artifacts, and their event streams end. The rest of the tree
     * stays stored, closed.
     */
    suspend fun delete(id: String) {
        val target = entries.known(id)
        val root = withContext(Dispatchers.IO) {
            try {
                entries.known(store.position(id).path.first())
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Unresolvable ancestry — unreadable metadata or a broken
                // parent link — must not make a session undeletable; such a
                // tree can never be attached, so the target's own entry
                // serializes the removal and there is nothing to tear down.
                target
            }
        }
        announcingChange {
            root.mutex.withLock {
                teardown(root)
                withContext(NonCancellable + Dispatchers.IO) {
                    removeSubtree(id)
                }
            }
        }
    }

    /**
     * Removes [id]'s stored subtree — registry entries, stored artifacts,
     * event streams ended — leaves first, so a removal cut short by process
     * death leaves a consistent tree, never children orphaned by a missing
     * parent link. Returns every removed session ID; the caller holds the
     * root's mutex.
     */
    private fun removeSubtree(id: String): List<String> {
        val members = store.subtreeIds(id)
        members.asReversed().forEach { member ->
            val memberEntry = entries.remove(member)
            store.delete(member)
            memberEntry?.eventSignal?.value = SESSION_DELETED_SIGNAL
        }
        return members
    }

    /**
     * Rewinds [id]: cuts its stored log so the event carrying
     * [firstDeletedSequenceId] and everything after it are deleted
     * permanently — no copy, no undo — under the appended
     * [AgentEvent.ConversationRewound] tail, and cascades into descendants
     * per [rewindPlan]'s rules — the returned IDs name every session the
     * cascade removed. An attached tree is reloaded from the cut logs over
     * the same environment, every live descendant dormant again and the
     * session promptable on return; a dormant tree stays dormant, resumed
     * from the cut logs by the next prompt.
     *
     * @throws InvalidRewindPointException when [firstDeletedSequenceId] is
     *   not in [id]'s own log — a sequence ID an earlier rewind deleted
     *   included — names an event a cut preserves, or is that log's first
     *   event, which no cut may delete.
     * @throws SessionConflictException when a run is in flight anywhere in
     *   the tree; nothing changes.
     */
    suspend fun rewind(id: String, firstDeletedSequenceId: Long): List<String> {
        val (path, root) = treeOf(entries, store, id)
        return announcingChange {
            root.mutex.withLock {
                if (root.runtime?.hasRunInFlight() == true) {
                    throw SessionConflictException("A run is in flight in the session's tree.")
                }
                val lastSurviving = lastSurvivorOfCutFrom(id, firstDeletedSequenceId)
                executeRewind(root, rootId = path.first(), id = id, lastSurviving = lastSurviving)
            }
        }
    }

    /**
     * Translates a cut starting at [firstDeletedSequenceId] into the last
     * surviving sequence ID every layer below takes: the greatest ID [id]'s
     * own log holds below it — read as the last entry below it, a stored log
     * ascending by sequence ID throughout.
     *
     * The log must hold the named event, and the event must be one a cut can
     * delete: an event a cut preserves would survive the very call naming it
     * first deleted, and the log's first event is the
     * [AgentEvent.SessionStarted] every read model over a log derives from,
     * so no cut may delete it.
     */
    private suspend fun lastSurvivorOfCutFrom(id: String, firstDeletedSequenceId: Long): Long {
        val events = storedEvents(id)
        val named = events.firstOrNull { it.sequenceId == firstDeletedSequenceId }
        val below = events.filter { it.sequenceId < firstDeletedSequenceId }
        val refusal = when {
            named == null -> "Session $id has no event with sequence ID $firstDeletedSequenceId to cut from."
            named.isPreservedByACut() ->
                "Sequence ID $firstDeletedSequenceId names an event a cut preserves in session $id's log, " +
                    "so it cannot be an event to cut from."
            below.isEmpty() ->
                "Sequence ID $firstDeletedSequenceId opens session $id's log, and a cut must leave it standing."
            else -> null
        }
        if (refusal != null) {
            throw InvalidRewindPointException(refusal)
        }
        return below.last().sequenceId
    }

    /** [id]'s stored events, off the caller's thread. */
    private suspend fun storedEvents(id: String): List<AgentEvent> = withContext(Dispatchers.IO) {
        try {
            store.readEvents(id)
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
    }

    /**
     * The rewind's mutating half; the caller holds the root's mutex and has
     * verified the tree idle and translated the request's cut point into
     * [lastSurviving]. Shielded like [teardown]: a caller's cancellation
     * must not abandon in-flight runs, open logs, or a half-applied plan.
     */
    private suspend fun executeRewind(
        root: SessionEntry,
        rootId: String,
        id: String,
        lastSurviving: Long,
    ): List<String> = withContext(NonCancellable + Dispatchers.IO) {
        val plan = rewindPlan(id, lastSurviving) { sessionId ->
            try {
                store.readEvents(sessionId)
            } catch (_: NoSuchFileException) {
                null
            }
        }
        val runtime = root.runtime
        if (runtime == null) applyPlan(plan) else rewindAttachedTree(root, rootId, runtime, plan)
    }

    /**
     * Cuts an attached tree's logs per [plan] and reloads the tree from
     * them over the same environment. A failure before the cut aborts the
     * rewind with every log untouched, tearing the tree down to closed; a
     * reload failure after it degrades the tree to closed with the rewind
     * standing — the next prompt surfaces the failure through the resume
     * path. Returns the deleted session IDs.
     */
    private suspend fun rewindAttachedTree(
        root: SessionEntry,
        rootId: String,
        runtime: TreeRuntime,
        plan: RewindPlan,
    ): List<String> {
        root.runtime = null
        runtime.abortRuns()
        runtime.closeLogs() // The open writers hold the inode the cut replaces.
        val harness = Harness.load(Path.of(store.position(rootId).root.harnessPath))
        val deleted = applyPlan(plan)
        runCatching { root.runtime = loadTree(root, rootId, harness, runtime.environment) }
        return deleted
    }

    /**
     * Deletions first, then the cuts — descendants before ancestors, the
     * target's own cut last, so an application cut short by process death
     * leaves every touched log parseable and no cut log referencing a child
     * state that was never cut to match. Returns the deleted session IDs.
     */
    private fun applyPlan(plan: RewindPlan): List<String> {
        val deleted = plan.deletedSubtreeRoots.flatMap { removeSubtree(it) }
        val gone = deleted.toSet()
        plan.cuts.reversed().forEach { (sessionId, lastSurviving) ->
            if (sessionId !in gone) {
                val rewound = store.rewindEvents(sessionId, lastSurviving)
                entries[sessionId]?.let { entry ->
                    entry.truncations.value += 1
                    entry.eventSignal.value = rewound.sequenceId
                }
            }
        }
        return deleted
    }

    /**
     * Starts a run over [prompt] on [id] under [settings], rebuilding its
     * tree's runtime when dormant and reviving just the chain from the
     * root down to [id].
     * Attach, navigation, and start happen under the root entry's mutex, so
     * a concurrent close cannot void an accepted prompt between them.
     *
     * @throws EventLogFailedException when the session's log stopped
     *   persisting: without it a run would leave no record.
     * @throws SessionConflictException when the session already has a run
     *   in flight — its own, or a parent-driven one.
     */
    suspend fun startRun(id: String, prompt: String, settings: RunSettings) {
        val (path, root) = treeOf(entries, store, id)
        root.mutex.withLock {
            launchRunLocked(root, path) { agent -> agent.send(prompt, settings) }
        }
    }

    /**
     * Retries [id]'s failed last run: cuts the stored log back to before
     * the LLM call that failed — per [retryPlan], which is also where a
     * session with nothing to retry draws its refusal — and resumes the
     * beheaded run under the settings it recorded, exactly as [rewind]
     * followed by a resume would. The cut announces itself to subscribers
     * as [AgentEvent.ConversationRewound]; the resumed run opens as
     * [AgentEvent.RunResumed] and runs like any prompted one.
     *
     * @throws SessionConflictException when the log's tail is not a failed
     *   run, or a run is in flight anywhere in the tree.
     */
    suspend fun retryRun(id: String) {
        val (path, root) = treeOf(entries, store, id)
        announcingChange {
            root.mutex.withLock {
                if (root.runtime?.hasRunInFlight() == true) {
                    throw SessionConflictException("A run is in flight in the session's tree.")
                }
                val plan = retryPlan(storedEvents(id))
                executeRewind(root, rootId = path.first(), id = id, lastSurviving = plan.lastSurvivingSequenceId)
                launchRunLocked(root, path) { agent -> agent.retry(plan.settings) }
            }
        }
    }

    /**
     * Attaches [path]'s tree when dormant — reviving just the chain from
     * the root down to its target — and launches [run] on the target's
     * agent; the caller holds the root's mutex.
     */
    private suspend fun launchRunLocked(
        root: SessionEntry,
        path: List<String>,
        run: suspend (Agent) -> RunResult,
    ) {
        val attached = root.runtime
        val runtime = attached ?: rebuild(root, path.first()).also { root.runtime = it }
        try {
            val agent = runtime.agentAt(path) ?: throw UnknownSessionException(path.last())
            runtime.launchRun(agent, run)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // A failed start must not leave behind the runtime it
            // attached — a member unreachable from its parent's log (a
            // torn spawn tail) or a failed revival throws here.
            if (attached == null) {
                teardown(root)
            }
            throw failure
        }
    }

    /**
     * Stream of [id]'s stored events via [SessionStore.tailEvents];
     * subscribing never attaches a dormant session.
     */
    fun eventsAfter(id: String, afterSequenceId: Long): Flow<StoredEvent> {
        val entry = entries.known(id)
        return store.tailEvents(id, entry.eventSignal, entry.truncations, afterSequenceId)
    }

    /** @throws UnknownSessionException when [id] names no known session. */
    fun requireKnown(id: String) {
        entries.known(id)
    }

    /**
     * @throws UnknownSessionException when [id] names no known session, or
     *   when [workspace] is not the workspace of [id]'s tree root — a session
     *   outside the caller's scope must look nonexistent rather than
     *   forbidden, since its existence is exactly what the scope hides.
     *
     * A subagent resolves through its root, whose workspace is the whole
     * tree's, so a child is in scope precisely when its root is. A session
     * whose stored workspace cannot be read at all is not in *anyone's* scope,
     * so unreadable state reads as unknown here rather than reporting itself:
     * [info] is where a corrupt session is diagnosed loudly, and it is
     * reachable unscoped, which is where a repair starts from.
     */
    suspend fun requireInWorkspace(id: String, workspace: String): Unit = withContext(Dispatchers.IO) {
        entries.known(id)
        val root = try {
            store.position(id).root
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted between lookup and read.
        } catch (_: CorruptSessionException) {
            throw UnknownSessionException(id)
        }
        if (normalizedWorkspace(root.environment.workspace) != normalizedWorkspace(workspace)) {
            throw UnknownSessionException(id)
        }
    }

    /** Raises one [sessionsChanged] signal; never suspends, never throws. */
    private fun announceChange() {
        changes.tryEmit(Unit)
    }

    /**
     * Runs [block], raising one [announceChange] signal as it leaves —
     * whether it returned, threw, or was cancelled.
     *
     * The `finally` is the point: the mutations this wraps outlive their
     * caller, [teardown] and [executeRewind] shielding themselves with
     * [NonCancellable] precisely so a client disconnecting mid-request cannot
     * abandon them half-done. An announcement after the call would be the
     * one part such a disconnect skips, leaving a session closed or deleted
     * with nobody told and no later signal standing for the missed one.
     * Announcing a mutation that failed instead costs a subscriber one
     * re-read finding nothing changed, which is what every signal invites.
     */
    private suspend fun <T> announcingChange(block: suspend () -> T): T = try {
        block()
    } finally {
        announceChange()
    }

    /** Closes every live tree; the stored sessions stay for the next process. */
    override fun close() {
        runBlocking {
            entries.keys.forEach { id ->
                runCatching { close(id) }
            }
        }
    }

    /** Rebuilds the tree runtime rooted at [id] from its stored artifacts; the caller holds the root's mutex. */
    private suspend fun rebuild(entry: SessionEntry, id: String): TreeRuntime = withContext(Dispatchers.IO) {
        val metadata = try {
            store.position(id).root
        } catch (_: NoSuchFileException) {
            throw UnknownSessionException(id) // Deleted while waiting on the mutex.
        }
        val harness = Harness.load(Path.of(metadata.harnessPath))
        loadTree(entry, id, harness, metadata.environment.build())
    }

    /** Loads the tree rooted at [id] from its stored log over [environment], with fresh open event logs. */
    private fun loadTree(
        entry: SessionEntry,
        id: String,
        harness: Harness,
        environment: ExecutionEnvironment,
    ): TreeRuntime {
        val events = store.readEvents(id)
        val eventLog = store.eventLogFor(id)
        val logs = ConcurrentHashMap<String, PersistedEventLog>()
        logs[id] = eventLog
        val agent = Agent.load(events, harness, client, environment, TreeMemberListener(logs, entry, eventLog, id))
        return TreeRuntime(agent, environment, logs, ::announceChange)
    }

    /**
     * One tree member's listener: the log first — the event is on disk when
     * the signal fires — then the wake-up. Every subagent the member
     * constructs (a spawn, or a revival) becomes a session here, before its
     * spawn event is observable: the child gets a registry entry, metadata
     * naming this member as its parent, and an open log registered in the
     * tree's [logs] for teardown — reusing whatever of that already exists.
     * Never throws: a child that cannot be persisted merely goes unobserved
     * instead of failing the spawn. Revival reads the stored logs back
     * through [storedEventsFor]; a deleted session yields null there.
     */
    private inner class TreeMemberListener(
        private val logs: ConcurrentHashMap<String, PersistedEventLog>,
        private val entry: SessionEntry,
        private val log: PersistedEventLog,
        sessionId: String?,
    ) : AgentEventListener {

        // A fresh session's ID becomes known with its first event.
        @Volatile
        private var sessionId: String? = sessionId

        override fun onEvent(event: AgentEvent) {
            if (sessionId == null && event is AgentEvent.SessionStarted) {
                sessionId = event.sessionId
            }
            log.onEvent(event)
            entry.eventSignal.value = event.sequenceId
        }

        override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener = try {
            attachChild(parentId = checkNotNull(this.sessionId), childId = sessionId)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            AgentEventListener { }
        }

        override suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = withContext(Dispatchers.IO) {
            try {
                store.readEvents(sessionId)
            } catch (_: NoSuchFileException) {
                null
            }
        }

        private fun attachChild(parentId: String, childId: String): TreeMemberListener {
            val childEntry = entries[childId] ?: SessionEntry().also { fresh ->
                store.writeMetadata(childId, SessionMetadata.Child(parentId))
                entries[childId] = fresh
            }
            val childLog = logs.computeIfAbsent(childId) { store.eventLogFor(childId) }
            return TreeMemberListener(logs, childEntry, childLog, childId)
        }
    }
}

/** The entry of [id]; sessions the registry does not know throw. */
private fun Map<String, SessionEntry>.known(id: String): SessionEntry =
    this[id] ?: throw UnknownSessionException(id)

/**
 * [id]'s path from its tree root — walked through the stored parent links —
 * plus the root's registry entry, whose mutex serializes the tree's
 * lifecycle. Metadata vanishing mid-walk reads as a deleted session.
 */
private suspend fun treeOf(
    entries: Map<String, SessionEntry>,
    store: SessionStore,
    id: String,
): Pair<List<String>, SessionEntry> = withContext(Dispatchers.IO) {
    entries.known(id)
    val path = try {
        store.position(id).path
    } catch (_: NoSuchFileException) {
        throw UnknownSessionException(id)
    }
    path to entries.known(path.first())
}

/** [id]'s position in its tree, resolved through the stored parent links. */
private fun SessionStore.position(id: String): TreePosition {
    val ancestry = mutableListOf(id)
    var current = readMetadata(id)
    while (current is SessionMetadata.Child) {
        check(current.parent !in ancestry) { "Stored session $id has a parent cycle in its metadata." }
        ancestry += current.parent
        current = readMetadata(current.parent)
    }
    return TreePosition(ancestry.asReversed(), current as SessionMetadata.Root)
}

/** [id] plus its stored descendants, discovered through the metadata parent links. */
private fun SessionStore.subtreeIds(id: String): List<String> {
    val childrenByParent = sessionIds().groupBy { sessionId ->
        (runCatching { readMetadata(sessionId) }.getOrNull() as? SessionMetadata.Child)?.parent
    }
    val subtree = mutableListOf(id)
    var index = 0
    while (index < subtree.size) {
        subtree += childrenByParent[subtree[index]].orEmpty()
        index++
    }
    return subtree
}

/** Tears down [rootEntry]'s tree runtime if attached; the caller holds the root's mutex. */
private suspend fun teardown(rootEntry: SessionEntry) {
    val runtime = rootEntry.runtime ?: return
    rootEntry.runtime = null
    // Shielded: a caller's cancellation (a client disconnecting
    // mid-request) must not abandon in-flight runs or open logs.
    withContext(NonCancellable + Dispatchers.IO) {
        runtime.abortRuns()
        runtime.closeLogs()
    }
}

/**
 * One registry slot. [eventSignal] carries the latest logged sequenceId as
 * the wake-up event tails wait on; it outlives the runtime so subscribers
 * of a dormant session see the events of a later reattachment.
 * [truncations] counts the rewinds that replaced the stored log — the
 * signal a tail reads to reopen a file shrunk under its open stream, which
 * a size comparison alone could miss once the log grows again. [runtime]
 * and [mutex] matter on tree roots only: the runtime belongs to the whole
 * tree, and every tree lifecycle transition serializes on its root's mutex.
 */
private class SessionEntry {

    val mutex = Mutex()

    val eventSignal = MutableStateFlow(BEFORE_FIRST_EVENT)

    val truncations = MutableStateFlow(0L)

    @Volatile
    var runtime: TreeRuntime? = null
}

/**
 * The ephemeral runtime attachment of a live subagent tree: the root
 * agent, the one environment every member executes in, and the open event
 * log of every member that has been live under this attachment.
 */
private class TreeRuntime(
    private val rootAgent: Agent,
    val environment: ExecutionEnvironment,
    private val logs: ConcurrentHashMap<String, PersistedEventLog>,
    private val announceChange: () -> Unit,
) {

    private val job = SupervisorJob()

    private val scope = CoroutineScope(job + Dispatchers.Default)

    /**
     * Session IDs with a server-started run in flight, covering the start
     * window — from [claimRun] until the agent is running — while
     * [Agent.isRunning] covers the run itself. A claim is dropped in the
     * run's own coroutine, so a stopper resuming on another can still read
     * the claim of the run it just ended.
     */
    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    /** The live-or-revived agent at [path] (root ID first); null when a link is unknown to its parent. */
    suspend fun agentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.subagentBySessionId(childId) }

    /**
     * Whether [path]'s member has a run in flight: a server-started one, or
     * one on the live agent. Walks live links only — a member without a
     * live agent cannot be running, and a status read must not revive one.
     */
    suspend fun isRunning(path: List<String>): Boolean =
        path.last() in activeRuns || liveAgentAt(path)?.isRunning == true

    /**
     * Whether any member of the whole tree has a run in flight. [activeRuns]
     * alone is tree-complete: every server-started run is claimed before
     * launch and unclaimed only after it fully ends, a parent-driven child
     * run exists only inside its parent's claimed run, and starts serialize
     * on the root mutex the caller already holds.
     */
    fun hasRunInFlight(): Boolean = activeRuns.isNotEmpty()

    private suspend fun liveAgentAt(path: List<String>): Agent? =
        path.drop(1).fold(rootAgent as Agent?) { agent, childId -> agent?.liveSubagentBySessionId(childId) }

    /**
     * The one way to drive a member of the tree: launches [run] — an
     * [Agent.send] or an [Agent.retry] on [agent] — as a child of the
     * tree's scope, so [abortRuns] reaches it, and returns as soon as it is
     * started. The run's outcome is never returned; its
     * [AgentEvent.RunFinished] log entry is the record.
     */
    fun launchRun(agent: Agent, run: suspend (Agent) -> RunResult) {
        logs[agent.sessionId]?.failure?.let { throw EventLogFailedException(it) }
        claimRun(agent)
        // Behind the claim, and at the end behind its release: the ordering
        // sessionsChanged promises.
        announceChange()
        scope.launch {
            try {
                run(agent)
            } finally {
                activeRuns.remove(agent.sessionId)
                announceChange()
            }
        }
    }

    /** Cancels every in-flight run in the tree and waits for the aborts; the runtime takes no further runs. */
    suspend fun abortRuns() {
        job.cancelAndJoin()
    }

    /** Stops the run in flight on [path]'s member, reached through live links only. */
    suspend fun stopRun(path: List<String>) {
        liveAgentAt(path)?.stop()
    }

    /** Closes every member's open log; the first failure propagates once all are closed. */
    fun closeLogs() {
        logs.values.map { runCatching { it.close() } }
            .firstNotNullOfOrNull { it.exceptionOrNull() }
            ?.let { throw it }
    }

    /**
     * Claims [agent]'s run slot, conflicting on an already-active run. The
     * unclaimed parent-driven case is a pre-check: should such a run start
     * concurrently, the agent's own busy guard fails the claimed run
     * without wedging the slot.
     */
    private fun claimRun(agent: Agent) {
        if (!activeRuns.add(agent.sessionId)) {
            throw SessionConflictException(RUN_ACTIVE_MESSAGE)
        }
        if (agent.isRunning) {
            activeRuns.remove(agent.sessionId)
            throw SessionConflictException(RUN_ACTIVE_MESSAGE)
        }
    }
}

/** [id]'s place in its subagent tree: the IDs from the root down to it, and the root's stored facts. */
private class TreePosition(val path: List<String>, val root: SessionMetadata.Root)

private const val RUN_ACTIVE_MESSAGE = "A run is already active on this session."
