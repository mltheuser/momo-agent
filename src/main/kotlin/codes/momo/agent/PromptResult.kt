package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/** Outcome of one [Agent.prompt] run. */
public data class PromptResult(
    /** How the run ended. */
    val status: Status,
    /** The model's answer; non-null exactly when [status] is [Status.COMPLETED]. */
    val finalMessage: String?,
    /**
     * Immutable snapshot of the full conversation at run end, earlier
     * [Agent.prompt] runs included.
     */
    val transcript: List<ChatMessage>,
    /** Token usage summed across this run's LLM responses. */
    val usage: ChatUsage,
    /** LLM calls this run made (retries not counted). */
    val turnsUsed: Int,
    /** Wall-clock time this run took. */
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

        /** The turn budget ran out while the model still wanted tool calls. */
        @SerialName("turns_exhausted")
        TURNS_EXHAUSTED,

        /** The wall-clock budget elapsed before the model answered. */
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
