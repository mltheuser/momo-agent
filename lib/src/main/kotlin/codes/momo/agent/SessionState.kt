package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException

/** What [Agent] construction starts a session from: freshly created, or restored from a stored event log. */
internal sealed interface SessionState {

    /** The session's title (for a restored session, the latest logged one). */
    val title: String

    /** Sequence ID of the next event the session emits. */
    val nextSequenceId: Long

    /** Conversation following the system message; empty for a fresh session. */
    val conversation: List<ChatMessage>

    /** Counters of the prompt the session is parked on, or null when it is not awaiting the user. */
    val parkedPrompt: PromptState?

    class Fresh(override val title: String) : SessionState {

        override val nextSequenceId: Long
            get() = 0

        override val conversation: List<ChatMessage>
            get() = emptyList()

        override val parkedPrompt: PromptState?
            get() = null
    }

    class Restored(
        val id: String,
        override val title: String,
        override val nextSequenceId: Long,
        override val conversation: List<ChatMessage>,
        override val parkedPrompt: PromptState?,
    ) : SessionState
}

/**
 * The [SessionState.Restored] a stored [events] log describes, validated
 * against the [harness] the session is being loaded into. Contract on
 * [Agent.load], the only caller.
 */
internal fun restoredSession(events: List<AgentEvent>, harness: Harness): SessionState.Restored {
    val started = requireNotNull(events.firstOrNull() as? AgentEvent.SessionStarted) {
        "Not a stored session log: the first event must be SessionStarted."
    }
    requireToolCallsSupported(events, harness)
    val parked = events.pendingQuestion() != null
    return SessionState.Restored(
        id = started.sessionId,
        title = events.filterIsInstance<AgentEvent.SessionRenamed>().lastOrNull()?.title ?: started.title,
        nextSequenceId = events.last().sequenceId + 1,
        conversation = conversationFrom(events, repairTail = !parked),
        parkedPrompt = if (parked) parkedPromptFrom(events) else null,
    )
}

/**
 * Every tool the log shows the session honoring — a call answered with a
 * non-error result, or parked on as a question — must be in the harness's
 * tool list. Calls that only ever drew error results (hallucinated and
 * unlisted names among them) never block loading: the log stays loadable
 * into the harness that produced it.
 */
private fun requireToolCallsSupported(events: List<AgentEvent>, harness: Harness) {
    val nameByCallId = events.filterIsInstance<AgentEvent.LlmCallFinished>()
        .flatMap { it.message.toolCalls.orEmpty() }
        .associate { it.id to it.function.name }
    val honored = events.mapNotNullTo(sortedSetOf()) { event ->
        when (event) {
            is AgentEvent.ToolCallFinished ->
                if (event.outcome == AgentEvent.ToolCallFinished.Outcome.ERROR) null else nameByCallId[event.callId]

            is AgentEvent.QuestionAsked -> nameByCallId[event.callId]

            else -> null
        }
    }
    val unsupported = honored - harness.tools.toSet()
    if (unsupported.isNotEmpty()) {
        throw HarnessValidationException(
            "The session log calls tools the harness does not include: ${unsupported.joinToString(", ")}. " +
                "Harness tools: ${harness.tools.joinToString(", ")}.",
        )
    }
}

/** The parked prompt's counters, rebuilt from its run's logged LLM calls; active time restarts at zero. */
private fun parkedPromptFrom(events: List<AgentEvent>): PromptState {
    val llmCalls = events
        .takeLastWhile { it !is AgentEvent.RunStarted }
        .filterIsInstance<AgentEvent.LlmCallFinished>()
    return PromptState(
        turnsUsed = llmCalls.size,
        usage = llmCalls.fold(ZERO_USAGE) { sum, call -> sum + call.usage },
    )
}

/**
 * The conversation the log's verbatim payloads describe, in event order,
 * repaired the way the live session's history was: tool calls a run left
 * unanswered get synthesized aborted results where that run ended — before
 * the next run's user message, or at the tail unless [repairTail] is false
 * (the log ends parked, its question still awaiting the answer).
 */
private fun conversationFrom(events: List<AgentEvent>, repairTail: Boolean): List<ChatMessage> = buildList {
    for (event in events) {
        when (event) {
            is AgentEvent.RunStarted -> {
                addAll(abortedToolResults(this))
                add(userMessage(event.userMessage))
            }

            is AgentEvent.LlmCallFinished -> add(event.message)

            is AgentEvent.ToolCallFinished -> add(toolResultMessage(event.callId, event.resultText))

            is AgentEvent.QuestionAnswered -> add(toolResultMessage(event.callId, event.answer))

            else -> Unit
        }
    }
    if (repairTail) {
        addAll(abortedToolResults(this))
    }
}
