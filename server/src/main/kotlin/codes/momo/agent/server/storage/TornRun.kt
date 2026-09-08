package codes.momo.agent.server.storage

import codes.momo.agent.AgentEvent
import codes.momo.agent.repairInterruptedRun
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException
import java.nio.file.Path

internal fun SessionStore.repairTornRun(id: String, timestampMillis: Long): List<AgentEvent> {
    val openRun = readingLog(id) { file -> file.openRunOf(id) } ?: return emptyList()
    val repairs = repairInterruptedRun(openRun.map { Json.decodeFromString<AgentEvent>(it.json) }, timestampMillis)
    try {
        writer(id).use { log -> repairs.forEach(log::onEvent) }
    } catch (failure: IOException) {
        throw EventLogFailedException(failure)
    }
    return repairs
}

internal fun openRunOf(linesNewestFirst: Sequence<LogLine>): List<LogLine>? {
    val tail = mutableListOf<LogLine>()
    val boundary = linesNewestFirst.onEach { tail += it }.firstOrNull { it.type in RUN_BOUNDARIES }
    return tail.asReversed().takeIf { boundary?.type in RUN_OPENERS }
}

private fun Path.openRunOf(sessionId: String): List<LogLine>? = readingBackwards { lines ->
    val parsed = lines.filter { it.isNotBlank() }
        .mapIndexedNotNull { indexFromEnd, line -> parseUnlessTorn(sessionId, indexFromEnd, line) }
    openRunOf(parsed)
}

private fun parseUnlessTorn(sessionId: String, indexFromEnd: Int, line: String): LogLine? = try {
    parseLogLine(line)
} catch (failure: SerializationException) {
    if (indexFromEnd == 0) null else throw CorruptSessionException(sessionId, failure.message, failure)
}

private inline fun <reified T : AgentEvent> storedType(): String = serializer<T>().descriptor.serialName

private val RUN_OPENERS: Set<String> = setOf(storedType<AgentEvent.RunStarted>(), storedType<AgentEvent.RunResumed>())

private val RUN_BOUNDARIES: Set<String> = RUN_OPENERS + storedType<AgentEvent.RunFinished>()
