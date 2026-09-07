package codes.momo.agent.server.storage

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

    fun readLines(id: String): List<LogLine> = readingLog(id) { file -> file.readLogLines(id, ::parseLogLine) }

    fun rewriteLog(id: String, lines: List<String>) {
        replaceAtomically(logFile(id), lines.joinToString(separator = "\n", postfix = "\n"))
    }

    fun tail(id: String, signal: EventLogSignal, afterSequenceId: Long): Flow<LogLine> =
        logFile(id).tailLogLines(signal, afterSequenceId)

    fun writer(id: String? = null): EventLogWriter = EventLogWriter(id, ::logFile)

    fun delete(id: String) {
        val directory = logFile(id).parent
        if (!directory.isDirectory()) {
            return
        }
        directory.listDirectoryEntries().forEach(Files::deleteIfExists)
        Files.deleteIfExists(directory)
    }

    private inline fun <T> readingLog(id: String, read: (Path) -> T): T = try {
        read(logFile(id))
    } catch (_: NoSuchFileException) {
        throw UnknownSessionException(id)
    }

    private fun logFile(id: String): Path = sessionsDir.resolve(id).resolve(EVENTS_FILE)
}

internal fun SessionStore.readEventsOrNull(id: String): List<AgentEvent>? = try {
    readEvents(id)
} catch (_: UnknownSessionException) {
    null
}

private const val EVENTS_FILE = "events.jsonl"
