package codes.momo.agent.internal

import ai.router.sdk.models.ChatMessage
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.harness.SUBAGENT_TOOL_NAMES
import codes.momo.agent.subagent.SpawnedChild
import java.util.UUID

internal sealed interface SessionState {

    val id: String

    val title: String

    val depth: Int

    val nextSequenceId: Long

    val conversation: List<ChatMessage>

    val spawned: Map<String, SpawnedChild>

    class Fresh(
        override val title: String,
        val parent: String? = null,
        override val depth: Int = 0,
    ) : SessionState {

        override val id: String = UUID.randomUUID().toString()

        override val nextSequenceId: Long
            get() = 0

        override val conversation: List<ChatMessage>
            get() = emptyList()

        override val spawned: Map<String, SpawnedChild>
            get() = emptyMap()
    }

    class Restored(
        override val id: String,
        override val title: String,
        override val depth: Int,
        override val nextSequenceId: Long,
        override val conversation: List<ChatMessage>,
        override val spawned: Map<String, SpawnedChild>,
    ) : SessionState
}

internal fun restoredSession(events: List<AgentEvent>, harness: Harness): SessionState.Restored {
    val started = requireNotNull(events.firstOrNull() as? AgentEvent.SessionStarted) {
        "Not a stored session log: the first event must be SessionStarted."
    }
    requireToolCallsSupported(events, harness)
    return SessionState.Restored(
        id = started.sessionId,
        title = events.filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title ?: started.title,
        depth = started.depth,
        nextSequenceId = events.last().sequenceId + 1,
        conversation = conversationFrom(events),

        spawned = events.filterIsInstance<AgentEvent.SubagentSpawned>()
            .associate { it.name to SpawnedChild(it.sessionId, it.type, it.modelId, it.reasoningEffort) },
    )
}

private fun requireToolCallsSupported(events: List<AgentEvent>, harness: Harness) {
    val nameByCallId = events.filterIsInstance<AgentEvent.LlmCallFinished>()
        .flatMap { it.message.toolCalls.orEmpty() }
        .associate { it.id to it.function.name }
    val honored = events.mapNotNullTo(sortedSetOf()) { event ->
        when (event) {
            is AgentEvent.ToolCallFinished ->
                if (event.outcome == AgentEvent.ToolCallFinished.Outcome.ERROR) null else nameByCallId[event.callId]

            else -> null
        }
    }
    val supported = harness.tools.toSet() +
        (if (harness.subagents.isEmpty()) emptySet() else SUBAGENT_TOOL_NAMES)
    val unsupported = honored - supported
    if (unsupported.isNotEmpty()) {
        throw HarnessValidationException(
            "The session log calls tools the harness does not include: ${unsupported.joinToString(", ")}. " +
                "Harness tools: ${harness.tools.joinToString(", ")}.",
        )
    }
}

private fun conversationFrom(events: List<AgentEvent>): List<ChatMessage> = buildList {
    val startedCallIds = mutableSetOf<String>()
    var runStatus: RunResult.Status? = null

    fun openRun() {
        addAll(toolCallRepairs(this, startedCallIds, runStatus))
        startedCallIds.clear()
        runStatus = null
    }

    for (event in events) {
        when (event) {
            is AgentEvent.RunStarted -> {
                openRun()
                add(userMessage(event.userMessage, event.attachments))
            }

            is AgentEvent.RunResumed -> openRun()

            is AgentEvent.RunFinished -> runStatus = event.status

            is AgentEvent.ToolCallStarted -> startedCallIds += event.callId

            is AgentEvent.LlmCallFinished -> add(event.message)

            is AgentEvent.ToolCallFinished -> add(toolResultMessage(event.callId, event.resultText, event.media))

            else -> Unit
        }
    }
    addAll(toolCallRepairs(this, startedCallIds, runStatus))
}
