package codes.momo.agent.server.session

import codes.momo.agent.AgentEvent
import codes.momo.agent.SessionSelection
import codes.momo.agent.harness.Harness
import codes.momo.agent.selection
import codes.momo.agent.server.storage.ifReadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.time.Duration

/** What a session list shows, and what every mutation answers with. Never loads the harness. */
@Serializable
internal data class SessionSummary(
    val id: String,
    val title: String,
    val status: SessionStatus,
    val updatedAtMillis: Long,
)

/** One session in full: the [SessionSummary]'s fields, then the detail. */
@Serializable
internal data class SessionInfo(
    val id: String,
    val title: String,
    val status: SessionStatus,
    val updatedAtMillis: Long,

    val parent: ParentSession?,
    val harnessPath: String,
    val workspace: String,
    val createdAtMillis: Long,

    val lastRun: RunStats?,

    val selection: SessionSelection,

    /** The tools taking a model in the session's harness tree, whatever the subagent depth limit offers. */
    val toolsWithModel: List<String>,
) {

    constructor(
        summary: SessionSummary,
        parent: ParentSession?,
        started: AgentEvent.SessionStarted,
        lastRun: RunStats?,
        selection: SessionSelection,
        toolsWithModel: List<String>,
    ) : this(
        id = summary.id,
        title = summary.title,
        status = summary.status,
        updatedAtMillis = summary.updatedAtMillis,
        parent = parent,
        harnessPath = started.harnessFolder,
        workspace = started.workspace,
        createdAtMillis = started.timestampMillis,
        lastRun = lastRun,
        selection = selection,
        toolsWithModel = toolsWithModel,
    )
}

@Serializable
internal data class ParentSession(val id: String, val title: String)

@Serializable
internal enum class SessionStatus {

    @SerialName("running")
    RUNNING,

    @SerialName("idle")
    IDLE,
}

@Serializable
internal data class RunStats(
    val turnsUsed: Int,
    val totalTokens: Int,

    val elapsed: Duration,
)

internal suspend fun SessionRegistry.list(workspace: String): List<SessionSummary> = withContext(Dispatchers.IO) {
    val scope = normalizedWorkspace(workspace)
    ids.mapNotNull { id ->
        store.ifReadable {
            readSessionStarted(id)
                .takeIf { it.parent == null && normalizedWorkspace(it.workspace) == scope }
                ?.let { started -> started.timestampMillis to summary(id) }
        }
    }.sortedBy { (createdAtMillis, _) -> createdAtMillis }.map { (_, summary) -> summary }
}

internal suspend fun SessionRegistry.summary(id: String): SessionSummary =
    readSettled(id) { tree, events -> summaryOf(id, tree, events) }

internal suspend fun SessionRegistry.info(id: String): SessionInfo = readSettled(id) { tree, events ->
    val started = events.sessionStarted()
    SessionInfo(
        summary = summaryOf(id, tree, events),
        parent = started.parent?.let { parentId ->
            ParentSession(parentId, store.readEvents(parentId).sessionTitle())
        },
        started = started,
        lastRun = events.lastRunStats(),
        selection = events.selection(),
        toolsWithModel = Harness.load(Path.of(started.harnessFolder)).toolsWithModel(),
    )
}

private suspend fun <T> SessionRegistry.readSettled(
    id: String,
    read: suspend (SessionTree, List<AgentEvent>) -> T,
): T {
    val tree = settledTreeOf(id)
    return withContext(Dispatchers.IO) { read(tree, store.readEvents(id)) }
}

private suspend fun summaryOf(id: String, tree: SessionTree, events: List<AgentEvent>): SessionSummary =
    SessionSummary(
        id = id,
        title = events.sessionTitle(),
        status = if (tree.root.run?.isRunning(tree.path) == true) SessionStatus.RUNNING else SessionStatus.IDLE,
        updatedAtMillis = events.sessionUpdatedAtMillis(),
    )

internal fun normalizedWorkspace(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

internal fun List<AgentEvent>.sessionStarted(): AgentEvent.SessionStarted = first() as AgentEvent.SessionStarted

internal val AgentEvent.SessionStarted.harnessFolder: String
    get() = checkNotNull(harnessPath) { "Session $sessionId runs a harness without a folder." }

internal fun List<AgentEvent>.sessionUpdatedAtMillis(): Long = last().timestampMillis

internal fun List<AgentEvent>.sessionTitle(): String =
    filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title ?: sessionStarted().title

internal fun List<AgentEvent>.lastRunStats(): RunStats? {
    if (none { it is AgentEvent.RunStarted }) {
        return null
    }
    val run = takeLastWhile { it !is AgentEvent.RunStarted }
    val finished = run.filterIsInstance<AgentEvent.RunFinished>().lastOrNull()
    return if (finished != null) {
        RunStats(finished.turnsUsed, finished.usage.totalTokens, finished.elapsed)
    } else {
        val turns = run.filterIsInstance<AgentEvent.LlmCallFinished>()
        RunStats(
            turnsUsed = turns.size,
            totalTokens = turns.sumOf { it.usage.totalTokens },
            elapsed = run.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
        )
    }
}
