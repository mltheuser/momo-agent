package codes.momo.agent.server

import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
internal sealed interface EnvironmentSpec {

    val workspace: String

    fun build(): ExecutionEnvironment = ExecutionEnvironment(Path.of(workspace))

    @Serializable
    @SerialName("local")
    data class Local(override val workspace: String) : EnvironmentSpec
}
