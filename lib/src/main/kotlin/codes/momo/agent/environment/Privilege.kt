package codes.momo.agent.environment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class Privilege {

    @SerialName("root")
    ROOT,

    @SerialName("passwordless_sudo")
    PASSWORDLESS_SUDO,

    @SerialName("unprivileged")
    UNPRIVILEGED,
}
