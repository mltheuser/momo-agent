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
import kotlin.io.path.useLines

internal class CorruptSessionException(id: String, cause: Exception) :
    RuntimeException("Stored session $id is unreadable: ${cause.message}", cause)

internal class SessionStore(dataDir: Path) {

    private val sessionsDir: Path = dataDir.resolve("sessions")

    fun sessionIds(): List<String> =
        if (sessionsDir.isDirectory()) {
            sessionsDir.listDirectoryEntries()
                .filter { it.resolve(EVENTS_FILE).isRegularFile() }
                .map { it.fileName.toString() }
        } else {
            emptyList()
        }

    fun readSessionStarted(id: String): AgentEvent.SessionStarted = try {
        val line = directory(id).resolve(EVENTS_FILE).useLines { lines -> lines.firstOrNull { it.isNotBlank() } }
        lineJson.decodeFromString(line.orEmpty())
    } catch (failure: SerializationException) {
        throw CorruptSessionException(id, failure)
    }

    fun readEvents(id: String): List<AgentEvent> =
        parseLogLines(id, directory(id).resolve(EVENTS_FILE)) { Json.decodeFromString(it) }

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

    fun eventLogForNewSession(): PersistedEventLog = PersistedEventLog(sessionsDir, id = null)

    fun eventLogFor(id: String): PersistedEventLog = PersistedEventLog(sessionsDir, id)

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

internal data class StoredEvent(val sequenceId: Long, val json: String)

@Serializable
private data class StoredLinePosition(val sequenceId: Long)

@Serializable
private data class StoredLineHeader(val sequenceId: Long, val type: String)

private val lineJson = Json { ignoreUnknownKeys = true }

private data class StoredLine(val header: StoredLineHeader, val json: String)

private fun storedLines(id: String, file: Path): List<StoredLine> = parseLogLines(id, file) { line ->
    StoredLine(lineJson.decodeFromString(line), line)
}

private fun StoredLine.survivesCut(lastSurvivingSequenceId: Long): Boolean =
    header.sequenceId <= lastSurvivingSequenceId || header.type in PRESERVED_EVENT_TYPES

private val PRESERVED_EVENTS: List<PreservedEvent> = listOf(
    preservedEvent<AgentEvent.SessionRenamed>(),
    preservedEvent<AgentEvent.ModelSelected>(),
)

private class PreservedEvent(val storedType: String, val matches: (AgentEvent) -> Boolean)

private inline fun <reified T : AgentEvent> preservedEvent(): PreservedEvent =
    PreservedEvent(serializer<T>().descriptor.serialName) { it is T }

internal val PRESERVED_EVENT_TYPES: Set<String> = PRESERVED_EVENTS.mapTo(mutableSetOf()) { it.storedType }

internal fun AgentEvent.isPreservedByACut(): Boolean = PRESERVED_EVENTS.any { it.matches(this) }

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

internal const val SESSION_DELETED_SIGNAL = Long.MIN_VALUE

internal const val BEFORE_FIRST_EVENT = -1L

private class LineTail(private val file: Path) : AutoCloseable {

    private var input: InputStream? = null

    private val partial = ByteArrayOutputStream()

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

private const val EVENTS_FILE = "events.jsonl"
