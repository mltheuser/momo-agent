package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.useLines

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

    fun readSessionStarted(id: String): AgentEvent.SessionStarted = readingLog(id) { file ->
        try {
            val line = file.useLines { lines -> lines.firstOrNull { it.isNotBlank() } }
            decodeLogLineAs(line.orEmpty())
        } catch (failure: SerializationException) {
            throw CorruptSessionException(id, failure)
        }
    }

    fun readEvents(id: String): List<AgentEvent> =
        readingLog(id) { file -> file.readLogLines(id) { Json.decodeFromString(it) } }

    fun tailEvents(
        id: String,
        signal: StateFlow<Long>,
        truncations: StateFlow<Long>,
        afterSequenceId: Long,
    ): Flow<LogLine> = flow {
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
        val lines = directory.resolve(EVENTS_FILE).readLogLines(id, ::parseLogLine)
        val rewound = AgentEvent.ConversationRewound(
            sequenceId = lines.last().sequenceId + 1,
            timestampMillis = System.currentTimeMillis(),
            lastSurvivingSequenceId = lastSurvivingSequenceId,
        )
        replaceAtomically(
            directory.resolve(EVENTS_FILE),
            buildString {
                lines.filter { it.survivesCut(lastSurvivingSequenceId) }.forEach { appendLine(it.json) }
                appendLine(encodeLogLine(rewound))
            },
        )
        return rewound
    }

    fun writer(id: String? = null): EventLogWriter = EventLogWriter(id, ::logFile)

    fun delete(id: String) {
        val directory = directory(id)
        if (!directory.isDirectory()) {
            return
        }
        directory.listDirectoryEntries().forEach(Files::deleteIfExists)
        Files.deleteIfExists(directory)
    }

    private inline fun <T> readingLog(id: String, read: (Path) -> T): T = try {
        read(directory(id).resolve(EVENTS_FILE))
    } catch (_: NoSuchFileException) {
        throw UnknownSessionException(id)
    }

    private fun directory(id: String): Path = sessionsDir.resolve(id)

    private fun logFile(id: String): Path = directory(id).resolve(EVENTS_FILE)
}

internal fun SessionStore.readEventsOrNull(id: String): List<AgentEvent>? = try {
    readEvents(id)
} catch (_: UnknownSessionException) {
    null
}

private fun LogLine.survivesCut(lastSurvivingSequenceId: Long): Boolean =
    sequenceId <= lastSurvivingSequenceId || type in PRESERVED_EVENT_TYPES

private suspend fun FlowCollector<LogLine>.drainNewLines(tail: LineTail, lastEmitted: Long): Long {
    var newest = lastEmitted
    var line = tail.nextLine()
    while (line != null) {
        if (line.isNotBlank()) {
            val parsed = parseLogLine(line)
            if (parsed.sequenceId > newest) {
                emit(parsed)
                newest = parsed.sequenceId
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

private const val EVENTS_FILE = "events.jsonl"
