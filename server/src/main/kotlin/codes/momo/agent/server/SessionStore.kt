package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * What the event log deliberately omits about a session: everything needed
 * to rebuild its runtime. Stored once per fact — a root owns the tree-wide
 * facts, a child only its place in the tree; a child's harness and
 * environment resolve through its root. The root's environment spec doubles
 * as the session's scope: its workspace is what
 * [SessionRegistry.list] filters on and what
 * [SessionRegistry.requireInWorkspace] compares against.
 */
@Serializable
internal sealed interface SessionMetadata {

    @Serializable
    @SerialName("root")
    data class Root(
        val harnessPath: String,
        val environment: EnvironmentSpec,
    ) : SessionMetadata

    @Serializable
    @SerialName("child")
    data class Child(
        /** Session ID of the immediate parent. */
        val parent: String,
    ) : SessionMetadata
}

/** Thrown when a session's stored files no longer parse. */
internal class CorruptSessionException(id: String, cause: Exception) :
    RuntimeException("Stored session $id is unreadable: ${cause.message}", cause)

/**
 * A session on disk — the durable half every running agent is an ephemeral
 * attachment to. Each session owns one folder under the data directory's
 * `sessions/`, named by its session ID and holding `session.json` (its
 * [SessionMetadata]) and `events.jsonl` (its event log, one serialized
 * [AgentEvent] per line, appended as emitted).
 */
internal class SessionStore(dataDir: Path) {

    private val sessionsDir: Path = dataDir.resolve("sessions")

    /** IDs of every stored session, however old the process that stored it. */
    fun sessionIds(): List<String> =
        if (sessionsDir.isDirectory()) {
            sessionsDir.listDirectoryEntries()
                .filter { it.resolve(METADATA_FILE).isRegularFile() }
                .map { it.fileName.toString() }
        } else {
            emptyList()
        }

    fun writeMetadata(id: String, metadata: SessionMetadata) {
        val directory = directory(id).createDirectories()
        replaceAtomically(directory.resolve(METADATA_FILE), metadataJson.encodeToString(metadata))
    }

    fun readMetadata(id: String): SessionMetadata = try {
        metadataJson.decodeFromString(directory(id).resolve(METADATA_FILE).readText())
    } catch (failure: SerializationException) {
        throw CorruptSessionException(id, failure)
    }

    /** The stored event log, oldest first, read with [parseLogLines]'s torn-tail tolerance. */
    fun readEvents(id: String): List<AgentEvent> =
        parseLogLines(id, directory(id).resolve(EVENTS_FILE)) { Json.decodeFromString(it) }

    /**
     * Tails [id]'s stored log as a cold flow: every event strictly after
     * [afterSequenceId], oldest first — the flushed history, then live
     * events as [signal] announces them — completing only when [signal]
     * announces [SESSION_DELETED_SIGNAL]. Each event's sequence ID is read
     * from its own stored line, never counted from its position (rewinds
     * leave gaps — see [AgentEvent.ConversationRewound]). When
     * [truncations] changes — a rewind replaced the file under the tail's
     * open stream, which still holds the replaced inode — the file is
     * reopened and re-read from the start, serving only what this tail has
     * not yet emitted; the [AgentEvent.ConversationRewound] the rewind
     * appended is new to every tail by its numbering contract, so every
     * subscriber converges on it. Torn lines are never served — see
     * [LineTail]; each subscriber reads the file independently at its own
     * pace.
     */
    fun tailEvents(
        id: String,
        signal: StateFlow<Long>,
        truncations: StateFlow<Long>,
        afterSequenceId: Long,
    ): Flow<StoredEvent> = flow {
        val file = directory(id).resolve(EVENTS_FILE)
        var tail = LineTail(file)
        try {
            var generation = truncations.value
            var lastEmitted = afterSequenceId
            signal.takeWhile { it != SESSION_DELETED_SIGNAL }.collect {
                val current = truncations.value
                if (current != generation) {
                    generation = current
                    tail.close()
                    tail = LineTail(file)
                }
                lastEmitted = drainNewLines(tail, lastEmitted)
            }
        } finally {
            tail.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cuts [id]'s stored log back to [lastSurvivingSequenceId] in one
     * atomic replacement: every line after it is deleted bar the
     * [PRESERVED_EVENT_TYPES] ones, and the returned
     * [AgentEvent.ConversationRewound] — numbered as its own KDoc pins —
     * becomes the new tail.
     */
    fun rewindEvents(id: String, lastSurvivingSequenceId: Long): AgentEvent.ConversationRewound {
        val directory = directory(id)
        val lines = storedLines(id, directory.resolve(EVENTS_FILE))
        val rewound = AgentEvent.ConversationRewound(
            sequenceId = lines.last().header.sequenceId + 1,
            timestampMillis = System.currentTimeMillis(),
            lastSurvivingSequenceId = lastSurvivingSequenceId,
        )
        replaceAtomically(
            directory.resolve(EVENTS_FILE),
            buildString {
                lines.filter { it.survivesCut(lastSurvivingSequenceId) }.forEach { appendLine(it.json) }
                appendLine(Json.encodeToString<AgentEvent>(rewound))
            },
        )
        return rewound
    }

    /** Listener persisting a fresh session's log; its `SessionStarted` event names the folder. */
    fun eventLogForNewSession(): PersistedEventLog = PersistedEventLog(sessionsDir, id = null)

    /** Listener appending to [id]'s existing log. */
    fun eventLogFor(id: String): PersistedEventLog = PersistedEventLog(sessionsDir, id)

    /** Removes every stored artifact of [id]. */
    fun delete(id: String) {
        val directory = directory(id)
        if (!directory.isDirectory()) {
            return
        }
        directory.listDirectoryEntries().forEach(Files::deleteIfExists)
        Files.deleteIfExists(directory)
    }

    private fun directory(id: String): Path = sessionsDir.resolve(id)
}

/** One stored event as the stream serves it: its own sequence ID plus its log line verbatim. */
internal data class StoredEvent(val sequenceId: Long, val json: String)

/** A stored line's place in its log: the sequence ID the event carries. */
@Serializable
private data class StoredLinePosition(val sequenceId: Long)

/** A stored line's header: its sequence ID and the stored wire name of its type. */
@Serializable
private data class StoredLineHeader(val sequenceId: Long, val type: String)

/** Reads a stored event line down to a [StoredLinePosition] or [StoredLineHeader], whatever else it carries. */
private val lineJson = Json { ignoreUnknownKeys = true }

/** One line of a log a rewind cuts: its [StoredLineHeader], plus the line verbatim. */
private data class StoredLine(val header: StoredLineHeader, val json: String)

/**
 * [id]'s stored log [file] as lines with their headers, read with
 * [parseLogLines]'s torn-tail tolerance. A complete line whose header does
 * not read fails the rewind as [CorruptSessionException] rather than being
 * dropped: refusing to cut a log we cannot read in full beats deleting
 * from it.
 */
private fun storedLines(id: String, file: Path): List<StoredLine> = parseLogLines(id, file) { line ->
    StoredLine(lineJson.decodeFromString(line), line)
}

/** Whether a cut back to [lastSurvivingSequenceId] keeps this line. */
private fun StoredLine.survivesCut(lastSurvivingSequenceId: Long): Boolean =
    header.sequenceId <= lastSurvivingSequenceId || header.type in PRESERVED_EVENT_TYPES

/**
 * The events a cut keeps wherever they sit in the log. Each is a derivation
 * input for user metadata the log happens to carry rather than conversation,
 * and a rewind edits only the conversation. A preserved line keeps its own
 * sequence ID, so it stands above the cut point, inside the gap the deletion
 * leaves. Another metadata event type joins by adding one [preservedEvent]
 * entry — the two views below both derive from this list, so neither can
 * name a type the other does not.
 */
private val PRESERVED_EVENTS: List<PreservedEvent> = listOf(
    preservedEvent<AgentEvent.SessionRenamed>(),
    preservedEvent<AgentEvent.ModelSelected>(),
)

/** One preserved event type: the wire name its stored line carries, and whether a decoded event is one. */
private class PreservedEvent(val storedType: String, val matches: (AgentEvent) -> Boolean)

/**
 * [T]'s [PRESERVED_EVENTS] entry, its wire name read off [T]'s own serializer
 * so a changed `@SerialName` cannot silently break the filter.
 */
private inline fun <reified T : AgentEvent> preservedEvent(): PreservedEvent =
    PreservedEvent(serializer<T>().descriptor.serialName) { it is T }

/** Stored wire names of [PRESERVED_EVENTS], for reading a log line's type discriminator. */
internal val PRESERVED_EVENT_TYPES: Set<String> = PRESERVED_EVENTS.mapTo(mutableSetOf()) { it.storedType }

/** Whether a cut keeps [this] wherever it sits, for a caller holding decoded events rather than log lines. */
internal fun AgentEvent.isPreservedByACut(): Boolean = PRESERVED_EVENTS.any { it.matches(this) }

/**
 * [id]'s stored log [file] as its non-blank lines, each decoded by [parse].
 * A trailing line torn by process death mid-write fails to decode and is
 * dropped; a decode failure anywhere earlier propagates as
 * [CorruptSessionException].
 */
private fun <T : Any> parseLogLines(id: String, file: Path, parse: (String) -> T): List<T> {
    val lines = file.readLines().filter { it.isNotBlank() }
    return lines.mapIndexedNotNull { index, line ->
        try {
            parse(line)
        } catch (failure: SerializationException) {
            if (index == lines.lastIndex) null else throw CorruptSessionException(id, failure)
        }
    }
}

/**
 * Emits every complete line [tail] holds beyond [lastEmitted] — reading
 * each line's own sequence ID — and returns the newest ID served, so a
 * reopened tail re-reading a rewound file repeats nothing.
 */
private suspend fun FlowCollector<StoredEvent>.drainNewLines(tail: LineTail, lastEmitted: Long): Long {
    var newest = lastEmitted
    var line = tail.nextLine()
    while (line != null) {
        if (line.isNotBlank()) {
            val sequenceId = lineJson.decodeFromString<StoredLinePosition>(line).sequenceId
            if (sequenceId > newest) {
                emit(StoredEvent(sequenceId, line))
                newest = sequenceId
            }
        }
        line = tail.nextLine()
    }
    return newest
}

/** Wake-up signal value announcing the session's deletion: its tails complete instead of waiting on. */
internal const val SESSION_DELETED_SIGNAL = Long.MIN_VALUE

/** Wake-up signal value before any event is logged; also the tail offset replaying everything. */
internal const val BEFORE_FIRST_EVENT = -1L

/**
 * Incremental reader over a growing log file, returning only complete —
 * newline-terminated — lines: the writer flushes each line with its
 * terminator, so a line without one is still being appended.
 */
private class LineTail(private val file: Path) : AutoCloseable {

    private var input: InputStream? = null

    private val partial = ByteArrayOutputStream()

    /** The next complete line, or null once everything flushed so far is consumed. */
    fun nextLine(): String? {
        val stream = input ?: openIfPresent() ?: return null
        var byte = stream.read()
        while (byte >= 0 && byte != '\n'.code) {
            partial.write(byte)
            byte = stream.read()
        }
        return if (byte < 0) {
            null
        } else {
            val line = partial.toString(Charsets.UTF_8)
            partial.reset()
            line
        }
    }

    override fun close() {
        input?.close()
    }

    private fun openIfPresent(): InputStream? = try {
        BufferedInputStream(Files.newInputStream(file)).also { input = it }
    } catch (_: NoSuchFileException) {
        null
    }
}

/**
 * [AgentEventListener] appending every event to its session's `events.jsonl`,
 * flushed per event so the stored log is current the moment the event
 * exists. Constructed without an ID for a fresh session, whose first event —
 * always [AgentEvent.SessionStarted] — names the folder to create.
 *
 * The first append failure stops the log — later events are dropped so the
 * intact prefix stays loadable — and is kept as [failure] until [close]
 * rethrows it.
 */
internal class PersistedEventLog(
    private val sessionsDir: Path,
    private var id: String?,
) : AgentEventListener, AutoCloseable {

    private var writer: BufferedWriter? = null

    @Volatile
    var failure: IOException? = null
        private set

    @Synchronized
    override fun onEvent(event: AgentEvent) {
        if (failure != null) {
            return
        }
        try {
            val target = writer ?: openWriter(event).also { writer = it }
            target.write(Json.encodeToString(event))
            target.newLine()
            target.flush()
        } catch (appendFailure: IOException) {
            failure = appendFailure
        }
    }

    @Synchronized
    override fun close() {
        try {
            writer?.close()
        } finally {
            writer = null
        }
        failure?.let { throw it }
    }

    private fun openWriter(event: AgentEvent): BufferedWriter {
        val sessionId = id
            ?: (event as? AgentEvent.SessionStarted)?.sessionId?.also { id = it }
            ?: error("a fresh session's first event must be SessionStarted, got: $event")
        val file = sessionsDir.resolve(sessionId).createDirectories().resolve(EVENTS_FILE)
        dropTornTail(file)
        return Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

/**
 * Truncates a trailing line torn by an earlier process death mid-append, so
 * the next append starts a fresh line instead of fusing with the torn bytes.
 */
private fun dropTornTail(file: Path) {
    val channel = try {
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
    } catch (_: NoSuchFileException) {
        return
    }
    channel.use {
        val size = it.size()
        val terminator = ByteBuffer.allocate(1)
        var end = size
        while (end > 0) {
            it.read(terminator.clear(), end - 1)
            if (terminator.get(0) == '\n'.code.toByte()) {
                break
            }
            end--
        }
        if (end < size) {
            it.truncate(end)
        }
    }
}

private const val METADATA_FILE = "session.json"

private const val EVENTS_FILE = "events.jsonl"

/**
 * Session metadata is read strictly, like the event log: a key the schema
 * does not know is a stored-format break, surfaced as a corrupt session
 * rather than papered over. A schema change is therefore a deliberate act
 * with a migration or a wipe, never a silent drift.
 */
private val metadataJson = Json
