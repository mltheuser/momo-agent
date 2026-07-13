package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * One entry in a session's event log — the session's single source of
 * truth. Conversation-bearing events carry their payloads verbatim, so a
 * stored log is enough both to replay a UI and to reconstruct the session
 * (see [Agent.load]). Harness and model details are deliberately absent:
 * they come from the harness a log is loaded into.
 */
@Serializable
public sealed interface AgentEvent {

    /** Position in the session's log: 0 for the first event, increasing by 1 per event. */
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
    ) : AgentEvent

    /**
     * A run reached a terminal status, however it ended. External
     * cancellation is the one exception: a log records a cancelled run
     * without this event, and
     * [Agent.load]'s transcript repair covers that missing tail. The
     * fields carry the final [RunResult] counterparts: the run's
     * totals, the final message verbatim.
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
