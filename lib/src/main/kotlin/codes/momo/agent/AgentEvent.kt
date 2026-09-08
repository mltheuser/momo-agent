package codes.momo.agent

import ai.router.sdk.models.AiRouterException
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ReasoningEffort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

@Serializable
public sealed interface AgentEvent {

    public val sequenceId: Long

    public val timestampMillis: Long

    @Serializable
    @SerialName("session_started")
    public data class SessionStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val sessionId: String,
        val title: String,
        val harnessPath: String?,
        val workspace: String,
        val parent: String? = null,
        val depth: Int = 0,
    ) : AgentEvent

    @Serializable
    @SerialName("session_renamed")
    public data class SessionRenamed(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val title: String,
    ) : AgentEvent

    @Serializable
    @SerialName("model_selected")
    public data class ModelSelected(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val model: String,

        val reasoningEffort: ReasoningEffort? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("run_started")
    public data class RunStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val userMessage: String,

        val model: String? = null,

        val reasoningEffort: ReasoningEffort? = null,

        val attachments: List<Attachment> = emptyList(),
    ) : AgentEvent {

        @Serializable
        public data class Attachment(val link: String, val mimeType: String, val base64Data: String)
    }

    @Serializable
    @SerialName("run_resumed")
    public data class RunResumed(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val model: String,
        val reasoningEffort: ReasoningEffort? = null,
    ) : AgentEvent

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

        val error: Error? = null,
    ) : AgentEvent {

        @Serializable
        public data class Error(

            val message: String,

            val type: String? = null,

            val statusCode: Int? = null,
        ) {

            internal companion object {

                fun from(failure: Exception): Error {
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

    @Serializable
    @SerialName("llm_call_started")
    public data class LlmCallStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val turn: Int,
    ) : AgentEvent

    @Serializable
    @SerialName("llm_call_retried")
    public data class LlmCallRetried(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val cause: String,

        val attempt: Int,
        val backoff: Duration,
    ) : AgentEvent

    @Serializable
    @SerialName("llm_call_finished")
    public data class LlmCallFinished(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val message: ChatMessage,
        val usage: ChatUsage,
        val finishReason: String,
    ) : AgentEvent

    @Serializable
    @SerialName("tool_call_started")
    public data class ToolCallStarted(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val callId: String,
        val toolName: String,
        val arguments: JsonObject,
    ) : AgentEvent

    @Serializable
    @SerialName("tool_call_finished")
    public data class ToolCallFinished(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val callId: String,
        val resultText: String,
        val outcome: Outcome,
        val duration: Duration,

        val truncated: Boolean,

        val media: Media? = null,
    ) : AgentEvent {

        @Serializable
        public data class Media(val mimeType: String, val base64Data: String)

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

    @Serializable
    @SerialName("subagent_spawned")
    public data class SubagentSpawned(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val name: String,
        val sessionId: String,

        @SerialName("subagentType")
        val type: String? = null,

        val modelId: String? = null,

        val reasoningEffort: ReasoningEffort? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("budget_updated")
    public data class BudgetUpdated(
        override val sequenceId: Long,
        override val timestampMillis: Long,
        val turnsUsed: Int,
        val turnsRemaining: Int,

        val elapsed: Duration,
    ) : AgentEvent
}
