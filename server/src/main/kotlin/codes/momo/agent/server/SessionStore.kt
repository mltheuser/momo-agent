package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What the event log deliberately omits about a session: everything needed
 * to rebuild its runtime, plus [Root.favorite] — the one stored fact that
 * is user metadata rather than a rebuild input. Stored once per fact — a
 * root owns the tree-wide facts, a child only its place in the tree; a
 * child's harness and environment resolve through its root.
 */
@Serializable
internal sealed interface SessionMetadata {

    @Serializable
    @SerialName("root")
    data class Root(
        val harnessPath: String,
        val environment: EnvironmentSpec,
        val favorite: Boolean = false,
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
        // Written via atomic replace: concurrent readers hold no lock, so
        // they must only ever see a complete file — old or new.
        val directory = directory(id).createDirectories()
        val staging = Files.createTempFile(directory, METADATA_FILE, ".tmp")
        staging.writeText(Json.encodeToString(metadata))
        Files.move(staging, directory.resolve(METADATA_FILE), StandardCopyOption.ATOMIC_MOVE)
    }

    fun readMetadata(id: String): SessionMetadata = try {
        Json.decodeFromString(directory(id).resolve(METADATA_FILE).readText())
    } catch (failure: SerializationException) {
        throw CorruptSessionException(id, failure)
    }

    /**
     * The stored event log, oldest first. A trailing line torn by process
     * death mid-write is dropped; corruption anywhere earlier propagates as
     * [CorruptSessionException].
     */
    fun readEvents(id: String): List<AgentEvent> {
        val lines = directory(id).resolve(EVENTS_FILE).readLines().filter { it.isNotBlank() }
        return lines.mapIndexedNotNull { index, line ->
            try {
                Json.decodeFromString<AgentEvent>(line)
            } catch (failure: SerializationException) {
                if (index == lines.lastIndex) null else throw CorruptSessionException(id, failure)
            }
        }
    }

    /**
     * Tails [id]'s stored log as a cold flow: every event strictly after
     * [afterSequenceId], oldest first — the flushed history, then live
     * events as [signal] announces them — completing only when [signal]
     * announces [SESSION_DELETED_SIGNAL]. Sequence IDs are the log's line
     * positions, the same gapless numbering the events carry. Torn lines
     * are never served — see [LineTail]; each subscriber reads the file
     * independently at its own pace.
     */
    fun tailEvents(id: String, signal: StateFlow<Long>, afterSequenceId: Long): Flow<StoredEvent> = flow {
        LineTail(directory(id).resolve(EVENTS_FILE)).use { tail ->
            var sequenceId = 0L
            signal.takeWhile { it != SESSION_DELETED_SIGNAL }.collect {
                var line = tail.nextLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        if (sequenceId > afterSequenceId) {
                            emit(StoredEvent(sequenceId, line))
                        }
                        sequenceId++
                    }
                    line = tail.nextLine()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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

/** One stored event as the stream serves it: its position plus its log line verbatim. */
internal data class StoredEvent(val sequenceId: Long, val json: String)

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
