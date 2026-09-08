package codes.momo.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public data class RunResult(

    val status: Status,

    val finalMessage: String?,

    val error: Exception?,
) {

    @Serializable
    public enum class Status {

        @SerialName("completed")
        COMPLETED,

        @SerialName("stopped")
        STOPPED,

        @SerialName("turns_exhausted")
        TURNS_EXHAUSTED,

        @SerialName("timeout")
        TIMEOUT,

        @SerialName("error")
        ERROR,

        @SerialName("interrupted")
        INTERRUPTED,
    }
}
