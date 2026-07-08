package codes.momo.agent.server

import codes.momo.agent.environment.ContainerExecutionEnvironment
import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Client-provided description of a session's execution environment, kept
 * verbatim in the session's metadata so a dormant session's runtime can be
 * rebuilt from it.
 */
@Serializable
internal sealed interface EnvironmentSpec {

    /**
     * Builds a fresh environment over the described workspace.
     *
     * @throws EnvironmentStartupException when the workspace or the
     *   environment's backend is unusable.
     */
    fun build(): ExecutionEnvironment

    @Serializable
    @SerialName("local")
    data class Local(val workspace: String) : EnvironmentSpec {

        override fun build(): ExecutionEnvironment = LocalExecutionEnvironment(Path.of(workspace))
    }

    @Serializable
    @SerialName("container")
    data class Container(val image: String, val workspace: String) : EnvironmentSpec {

        override fun build(): ExecutionEnvironment = ContainerExecutionEnvironment(image, Path.of(workspace))
    }
}
