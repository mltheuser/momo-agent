package codes.momo.agent

import ai.router.sdk.models.AiRouterException
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

    /**
     * A client recorded the model selection for the session's next prompt —
     * user metadata the log carries like the title, written by
     * [Agent.recordModelSelection] and never read by the agent itself: every
     * run still carries its own [RunSettings].
     */
    @Serializable
    @SerialName("model_selected")
    public data class ModelSelected(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val model: String,
        /** The selection's reasoning effort; null for the provider default. */
        val reasoningEffort: ReasoningEffort? = null,
    ) : AgentEvent

    /**
     * An [Agent.send] run began; [userMessage] is the verbatim user text.
     * [attachments] carries the images its markdown image links resolved
     * to at send time, so replay rebuilds the run's exact multi-part user
     * message from the log alone — the linked files may have changed or
     * vanished since.
     */
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
        /** One entry per resolved image link; empty when none resolved (and for logs predating the field). */
        val attachments: List<Attachment> = emptyList(),
    ) : AgentEvent {

        /** One resolved prompt image, verbatim as loaded — the log is the conversation, blobs included. */
        @Serializable
        public data class Attachment(val link: String, val mimeType: String, val base64Data: String)
    }

    /**
     * An [Agent.retry] resumed the conversation's beheaded run: no new user
     * message, so no [RunStarted] — this event is what marks the resumed
     * run in flight, carrying its [RunSettings] fields the way [RunStarted]
     * does. It bears no conversation content (transcript derivation
     * ignores it).
     */
    @Serializable
    @SerialName("run_resumed")
    public data class RunResumed(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val model: String,
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
        /** What failed; absent for every non-ERROR status and for logs predating the field. */
        val error: Error? = null,
    ) : AgentEvent {

        /**
         * The failure behind an ERROR outcome, forwarded verbatim — never
         * reworded or re-classified. [type] and [statusCode] are present
         * only when the failure was an
         * [ai.router.sdk.models.AiRouterException].
         */
        @Serializable
        public data class Error(
            /** The throwable's message, or its class name when that was null or blank — never empty. */
            val message: String,
            /** ai-router's error type. */
            val type: String? = null,
            /** The HTTP status ai-router reported. */
            val statusCode: Int? = null,
        ) {

            internal companion object {

                /** [failure] as the event carries it. */
                fun from(failure: Throwable): Error {
                    val routerFailure = failure as? AiRouterException
                    return Error(
                        message = failure.message?.takeUnless { it.isBlank() } ?: failure::class.java.name,
                        type = routerFailure?.apiError?.type,
                        statusCode = routerFailure?.statusCode,
                    )
                }
            }
        }
    }

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

    /**
     * A tool call finished. For a text result [resultText] is the exact
     * model-facing text appended to the conversation; for a media result
     * the appended tool message carries [media] as an image content part
     * instead, and [resultText] is only a marker for renderers.
     */
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
        /** The image a media-bearing result put in front of the model; null for text results and older logs. */
        val media: Media? = null,
    ) : AgentEvent {

        /** An image payload, verbatim as the tool produced it — the log is the conversation, blobs included. */
        @Serializable
        public data class Media(val mimeType: String, val base64Data: String)

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
        /** Reasoning effort of the parent's driven runs of this child; null to inherit each driving run's. */
        val reasoningEffort: ReasoningEffort? = null,
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
