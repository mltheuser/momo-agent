package codes.momo.agent.environment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rights the commands of an [ExecutionEnvironment] run with. Root and
 * passwordless sudo stay distinguishable because they need opposite
 * guidance: under root, writing `sudo` is the mistake; under passwordless
 * sudo, omitting it is.
 *
 * The wire names are a compatibility contract — they are stored in session
 * metadata, so renaming one breaks every session written before the rename.
 */
@Serializable
public enum class Privilege {

    /** Commands run as root already; nothing needs elevating. */
    @SerialName("root")
    ROOT,

    /** Commands run unprivileged, but `sudo` elevates them without asking for a password. */
    @SerialName("passwordless_sudo")
    PASSWORDLESS_SUDO,

    /** Commands run unprivileged with no way up. */
    @SerialName("unprivileged")
    UNPRIVILEGED,
}
