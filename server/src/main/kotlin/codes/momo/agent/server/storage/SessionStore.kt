package codes.momo.agent.server.storage

import codes.momo.agent.AgentEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
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
        val line = file.useLines { lines -> lines.firstOrNull { it.isNotBlank() } }
        decodeLogLineAs(line.orEmpty())
    }

    fun readEvents(id: String): List<AgentEvent> = readingLog(id) { file ->
        val events = file.readLogLines<AgentEvent> { Json.decodeFromString(it) }
        if (events.firstOrNull() !is AgentEvent.SessionStarted) {
            throw CorruptSessionException(id, "the log does not begin with a session_started event")
        }
        events
    }

    fun readLines(id: String): List<LogLine> = readingLog(id) { file -> file.readLogLines(::parseLogLine) }

    fun lastWrittenMillis(id: String): Long = readingLog(id) { file -> file.getLastModifiedTime().toMillis() }

    fun rewriteLog(id: String, lines: List<String>) {
        replaceAtomically(logFile(id), lines.joinToString(separator = "\n", postfix = "\n"))
    }

    fun writer(id: String? = null): EventLogWriter = EventLogWriter(id, ::logFile)

    fun delete(id: String) {
        val directory = logFile(id).parent
        if (!directory.isDirectory()) {
            return
        }
        directory.listDirectoryEntries().forEach(Files::deleteIfExists)
        Files.deleteIfExists(directory)
    }

    inline fun <T> readingLog(id: String, read: (Path) -> T): T = try {
        read(logFile(id))
    } catch (_: NoSuchFileException) {
        throw UnknownSessionException(id)
    } catch (failure: IOException) {
        throw CorruptSessionException(id, failure.message, failure)
    } catch (failure: SerializationException) {
        throw CorruptSessionException(id, failure.message, failure)
    }

    private fun logFile(id: String): Path = sessionsDir.resolve(id).resolve(EVENTS_FILE)
}

internal fun SessionStore.readEventsOrNull(id: String): List<AgentEvent>? = try {
    readEvents(id)
} catch (_: UnknownSessionException) {
    null
}

internal inline fun <T> SessionStore.ifReadable(read: SessionStore.() -> T): T? = try {
    read()
} catch (_: UnknownSessionException) {
    null
} catch (_: CorruptSessionException) {
    null
}

internal fun SessionStore.pathTo(started: AgentEvent.SessionStarted): List<String> {
    val ancestry = mutableListOf(started.sessionId)
    var parent = started.parent
    while (parent != null) {
        if (parent in ancestry) {
            throw CorruptSessionException(started.sessionId, "its parent chain is a cycle")
        }
        ancestry += parent
        parent = readSessionStarted(parent).parent
    }
    return ancestry.asReversed()
}

private const val EVENTS_FILE = "events.jsonl"
