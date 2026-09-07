package codes.momo.agent

import ai.router.sdk.models.ChatUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

public data class RunResult(

    val status: Status,

    val finalMessage: String?,

    val usage: ChatUsage,

    val turnsUsed: Int,

    val elapsed: Duration,

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
    }
}
