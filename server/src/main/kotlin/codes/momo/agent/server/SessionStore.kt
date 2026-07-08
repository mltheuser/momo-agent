package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
 * to rebuild its runtime.
 */
@Serializable
internal data class SessionMetadata(
    val harnessPath: String,
    /** The harness's model string when the session was created. */
    val model: String,
    val environment: EnvironmentSpec,
)

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
        directory(id).createDirectories()
        directory(id).resolve(METADATA_FILE).writeText(Json.encodeToString(metadata))
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
        return Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

private const val METADATA_FILE = "session.json"

private const val EVENTS_FILE = "events.jsonl"
