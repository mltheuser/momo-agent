package codes.momo.agent.server

import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Client-provided description of a session's execution environment, kept
 * verbatim in the session's metadata so a dormant session's runtime can be
 * rebuilt from it.
 *
 * Execution is always local, so the one variant describes only the
 * workspace. It stays a tagged union regardless: the `local` tag is the
 * wire and storage contract — every stored `session.json` carries it — and
 * the tag is what makes a request naming a retired variant (`container`)
 * fail loudly instead of quietly building something else.
 *
 * The privilege a session's commands run with is not described here — it
 * follows from the account the server process runs as — so it is discovered
 * when the environment is built and reported through [SessionInfo.privilege],
 * never accepted from a client.
 */
@Serializable
internal sealed interface EnvironmentSpec {

    /** Absolute path of the folder the session's commands run in. */
    val workspace: String

    /**
     * Builds a fresh environment over the described workspace.
     *
     * @throws EnvironmentStartupException when the workspace is unusable.
     */
    fun build(): ExecutionEnvironment = ExecutionEnvironment(Path.of(workspace))

    @Serializable
    @SerialName("local")
    data class Local(override val workspace: String) : EnvironmentSpec
}
