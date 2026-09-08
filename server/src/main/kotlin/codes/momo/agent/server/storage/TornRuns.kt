package codes.momo.agent.server.storage

import ai.router.sdk.models.ChatUsage
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.file.Path
import kotlin.time.Duration

internal fun openRunOf(linesNewestFirst: Sequence<LogLine>): List<LogLine>? {
    val tail = mutableListOf<LogLine>()
    val boundary = linesNewestFirst.onEach { tail += it }.firstOrNull { it.type in RUN_BOUNDARIES }
    return tail.asReversed().takeIf { boundary?.type in RUN_OPENERS }
}

internal fun interruptedRunFinished(openRun: List<LogLine>, timestampMillis: Long): AgentEvent.RunFinished {
    val events = openRun.map { Json.decodeFromString<AgentEvent>(it.json) }
    val turns = events.filterIsInstance<AgentEvent.LlmCallFinished>()
    return AgentEvent.RunFinished(
        sequenceId = openRun.last().sequenceId + 1,
        timestampMillis = timestampMillis,
        status = RunResult.Status.INTERRUPTED,
        finalMessage = null,
        usage = ChatUsage(
            promptTokens = turns.sumOf { it.usage.promptTokens },
            completionTokens = turns.sumOf { it.usage.completionTokens },
            totalTokens = turns.sumOf { it.usage.totalTokens },
            reasoningTokens = turns.sumOf { it.usage.reasoningTokens },
            cacheReadTokens = turns.sumOf { it.usage.cacheReadTokens },
        ),
        turnsUsed = turns.size,
        elapsed = events.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
    )
}

internal fun SessionStore.repairTornRuns(): List<String> = sessionIds().filter { id ->
    val openRun = ifReadable { readingLog(id) { file -> file.openRunOf(id) } } ?: return@filter false
    writer(id).use { it.onEvent(interruptedRunFinished(openRun, System.currentTimeMillis())) }
    true
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

private val RUN_BOUNDARIES: Set<String> =
    RUN_OPENERS + storedType<AgentEvent.RunFinished>() + storedType<AgentEvent.ConversationRewound>()
