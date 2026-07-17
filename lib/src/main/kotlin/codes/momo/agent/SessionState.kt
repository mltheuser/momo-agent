package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.tool.SUBAGENT_TOOL_NAMES
import java.util.UUID

/** What [Agent] construction starts a session from: freshly created, or restored from a stored event log. */
internal sealed interface SessionState {

    /** Stable session identity: generated fresh, recovered from the log when restored. */
    val id: String

    /** The session's title (for a restored session, the latest logged one). */
    val title: String

    /** Nesting depth in the subagent tree: 0 for a root; recovered from the log when restored. */
    val depth: Int

    /** Sequence ID of the next event the session emits. */
    val nextSequenceId: Long

    /** Conversation following the system message; empty for a fresh session. */
    val conversation: List<ChatMessage>

    /** The session's already-spawned subagents by name; dormant until revived. */
    val spawned: Map<String, SpawnedChild>

    class Fresh(override val title: String, override val depth: Int = 0) : SessionState {

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

/** One already-spawned child as its [AgentEvent.SubagentSpawned] recorded it. */
internal class SpawnedChild(val sessionId: String, val type: String?, val modelId: String?)

/**
 * The [SessionState.Restored] a stored [events] log describes, validated
 * against the [harness] the session is being loaded into. Contract on
 * [Agent.load]; subagent revival restores through the same path.
 */
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
        // Last spawn per name wins: a name freed by a lost child log may
        // have been reused by a later spawn.
        spawned = events.filterIsInstance<AgentEvent.SubagentSpawned>()
            .associate { it.name to SpawnedChild(it.sessionId, it.type, it.modelId) },
    )
}

/**
 * Every tool the log shows the session honoring — a call answered with a
 * non-error result — must be in the harness's tool list, the implied
 * subagent tools of a harness declaring subagent types included. Calls
 * that only ever drew error results (hallucinated and unlisted names among
 * them) never block loading: the log stays loadable into the harness that
 * produced it.
 */
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

/**
 * The conversation the log's verbatim payloads describe, in event order,
 * repaired the way the live session's history was: tool calls a run left
 * unanswered get synthesized aborted results where that run ended — before
 * the next run's user message, or at the tail.
 */
private fun conversationFrom(events: List<AgentEvent>): List<ChatMessage> = buildList {
    for (event in events) {
        when (event) {
            is AgentEvent.RunStarted -> {
                addAll(abortedToolResults(this))
                add(userMessage(event.userMessage))
            }

            is AgentEvent.LlmCallFinished -> add(event.message)

            is AgentEvent.ToolCallFinished -> add(toolResultMessage(event.callId, event.resultText))

            else -> Unit
        }
    }
    addAll(abortedToolResults(this))
}
