package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Outcome of one [Agent.send] call. A prompt spans pause-separated
 * segments: [Status.AWAITING_USER] parks it, the answering send resumes
 * it.
 */
public data class PromptResult(
    /** How this segment ended. */
    val status: Status,
    /** The model's answer; non-null exactly when [status] is [Status.COMPLETED]. */
    val finalMessage: String?,
    /** The question awaiting the user; non-null exactly when [status] is [Status.AWAITING_USER]. */
    val pendingQuestion: String?,
    /**
     * Immutable snapshot of the full conversation at segment end, earlier
     * prompts included.
     */
    val transcript: List<ChatMessage>,
    /** Token usage summed across the prompt's LLM responses. */
    val usage: ChatUsage,
    /** LLM calls the prompt made (retries not counted). */
    val turnsUsed: Int,
    /** Active wall-clock time the prompt consumed. */
    val elapsed: Duration,
    /** What failed; non-null exactly when [status] is [Status.ERROR]. */
    val error: Throwable?,
) {

    /** Serial names are part of the stored event-log format — see [AgentEvent]. */
    @Serializable
    public enum class Status {
        /** The model answered without tool calls; the final message is that answer. */
        @SerialName("completed")
        COMPLETED,

        /** The model asked the user a question; the prompt is parked until [Agent.send] delivers the answer. */
        @SerialName("awaiting_user")
        AWAITING_USER,

        /** The turn budget ran out while the model still wanted tool calls. */
        @SerialName("turns_exhausted")
        TURNS_EXHAUSTED,

        /** The active wall-clock budget was spent before the model answered. */
        @SerialName("timeout")
        TIMEOUT,

        /**
         * An LLM call failed terminally — non-transiently, past the retry
         * cap, or with a response reporting a failed finish.
         */
        @SerialName("error")
        ERROR,
    }
}
