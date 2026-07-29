package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ReasoningEffort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * One entry in a session's event log — the session's single source of
 * truth. Conversation-bearing events carry their payloads verbatim, so a
 * stored log is enough both to replay a UI and to reconstruct the session
 * (see [Agent.load]). Each run records the model and reasoning effort it
 * used on its [RunStarted]; harness details beyond that come from the
 * harness a log is loaded into.
 */
@Serializable
public sealed interface AgentEvent {

    /**
     * Identity and order in the session's log: 0 for the first event,
     * strictly increasing per event — but not the log's line position,
     * since a rewind deletes events without renumbering the survivors
     * (see [ConversationRewound]).
     */
    public val sequenceId: Long

    /** Wall-clock time of emission, as epoch milliseconds. */
    public val timestampMillis: Long

    /** A fresh session came into existence; always a log's first event. */
    @Serializable
    @SerialName("session_started")
    public data class SessionStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val sessionId: String,
        val title: String,
        /** Nesting depth in the subagent tree: 0 for a root session (and for logs predating the field). */
        val depth: Int = 0,
    ) : AgentEvent

    /** The session's title changed; the last such event in a log names it. */
    @Serializable
    @SerialName("session_renamed")
    public data class SessionRenamed(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val title: String,
    ) : AgentEvent

    /** An [Agent.send] run began; [userMessage] is the verbatim user text. */
    @Serializable
    @SerialName("run_started")
    public data class RunStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val userMessage: String,
        /** The model the run calls the LLM with; null for logs predating the field. */
        val model: String? = null,
        /** The run's [RunSettings.reasoningEffort]; null when unset. */
        val reasoningEffort: ReasoningEffort? = null,
    ) : AgentEvent

    /**
     * A run reached a terminal status, however it ended — an [Agent.stop]
     * included. A log can still record a run without this event: cancelling
     * the coroutine that runs [Agent.send] leaves one, as does this event's
     * own emit raising what the [AgentEventListener] contract does not
     * swallow. [Agent.load]'s transcript repair covers that missing tail
     * either way. The fields carry the final [RunResult] counterparts: the
     * run's totals, the final message verbatim.
     */
    @Serializable
    @SerialName("run_finished")
    public data class RunFinished(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val status: RunResult.Status,
        val finalMessage: String?,
        val usage: ChatUsage,
        val turnsUsed: Int,
        val elapsed: Duration,
    ) : AgentEvent

    /** An LLM call went out for the 1-based [turn] of the current run. */
    @Serializable
    @SerialName("llm_call_started")
    public data class LlmCallStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val turn: Int,
    ) : AgentEvent

    /** An LLM call failed transiently and is retried after sleeping [backoff]. */
    @Serializable
    @SerialName("llm_call_retried")
    public data class LlmCallRetried(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val cause: String,
        /** 1-based number of the upcoming retry. */
        val attempt: Int,
        val backoff: Duration,
    ) : AgentEvent

    /** An LLM call succeeded; [message] is the verbatim assistant message, tool calls included. */
    @Serializable
    @SerialName("llm_call_finished")
    public data class LlmCallFinished(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val message: ChatMessage,
        val usage: ChatUsage,
        val finishReason: String,
    ) : AgentEvent

    /** A requested tool call started executing, with the model's verbatim arguments. */
    @Serializable
    @SerialName("tool_call_started")
    public data class ToolCallStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val callId: String,
        val toolName: String,
        val arguments: JsonObject,
    ) : AgentEvent

    /** A tool call finished; [resultText] is the exact model-facing text appended to the conversation. */
    @Serializable
    @SerialName("tool_call_finished")
    public data class ToolCallFinished(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val callId: String,
        val resultText: String,
        val outcome: Outcome,
        val duration: Duration,
        /** The dispatch's [codes.momo.agent.tool.ToolExecution.truncated]. */
        val truncated: Boolean,
    ) : AgentEvent {

        /** How the execution ended, mirroring the [codes.momo.agent.tool.ToolResult] variants. */
        @Serializable
        public enum class Outcome {
            @SerialName("success")
            SUCCESS,

            @SerialName("error")
            ERROR,

            @SerialName("timed_out")
            TIMED_OUT,
        }
    }

    /**
     * A child agent was allocated under [name]; [sessionId] identifies the
     * child session, whose own event log records everything it does.
     */
    @Serializable
    @SerialName("subagent_spawned")
    public data class SubagentSpawned(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val name: String,
        val sessionId: String,
        /** The declared subagent type spawned; null for logs predating typed spawning. */
        @SerialName("subagentType")
        val type: String? = null,
        /** Model the parent's driven runs of this child use; null to inherit each driving run's. */
        val modelId: String? = null,
    ) : AgentEvent

    /**
     * A rewind truncated the log: the conversation after
     * [lastSurvivingSequenceId] was deleted permanently and this event was
     * appended as the new tail, numbered above the log's pre-cut maximum.
     * Other events can survive above the cut point, so a reader must not
     * treat everything above it as deleted. This event carries no
     * conversation content (transcript derivation ignores it) and closes
     * any run the cut beheaded: a dangling [RunStarted] before it must not
     * be read as a run still in flight.
     */
    @Serializable
    @SerialName("conversation_rewound")
    public data class ConversationRewound(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        /** The cut point: the sequence ID the conversation was cut back to, whatever survives above it. */
        val lastSurvivingSequenceId: Long,
    ) : AgentEvent

    /** Budget accounting at a turn boundary. */
    @Serializable
    @SerialName("budget_updated")
    public data class BudgetUpdated(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val turnsUsed: Int,
        val turnsRemaining: Int,
        /** The run's [RunResult.elapsed] so far. */
        val elapsed: Duration,
    ) : AgentEvent
}
