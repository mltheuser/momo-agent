package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

    fun tail(id: String, signal: EventLogSignal, afterSequenceId: Long): Flow<LogLine> =
        logFile(id).tailLogLines(signal, afterSequenceId)

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

private const val EVENTS_FILE = "events.jsonl"
