package codes.momo.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public data class RunResult(

    val status: Status,

    val finalMessage: String?,

    val error: Exception?,

    val lastCallPromptTokens: Int?,
) {

    @Serializable
    public enum class Status {

        @SerialName("completed")
        COMPLETED,

        @SerialName("stopped")
        STOPPED,

        @SerialName("turns_exhausted")
        TURNS_EXHAUSTED,

        @SerialName("error")
        ERROR,

        @SerialName("interrupted")
        INTERRUPTED,
    }
}
