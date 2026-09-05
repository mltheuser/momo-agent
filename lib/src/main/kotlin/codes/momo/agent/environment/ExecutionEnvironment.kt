package codes.momo.agent.environment

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration

public class ExecutionEnvironment internal constructor(
    workspace: Path,
    searchPath: String?,
    probe: PrivilegeProbe = hostPrivilegeProbe(workspace),
    private val runner: CommandRunner = hostCommandRunner(workspace),
) {

    public constructor(workspace: Path) : this(workspace, System.getenv("PATH"))

    init {
        if (!workspace.isDirectory()) {
            throw EnvironmentStartupException(
                "Workspace folder not found (or not a directory): $workspace",
            )
        }
        val missing = USERLAND_BASELINE.filterNot { isOnSearchPath(it, searchPath) }
        if (missing.isNotEmpty()) {
            throw EnvironmentStartupException(
                "Host userland baseline is incomplete — required binaries not found on PATH: " +
                    "${missing.joinToString(", ")}. Install them (or fix PATH) and retry.",
            )
        }
    }

    public val privilege: Privilege = probe.detect()

    public val workspacePath: String = workspace.toAbsolutePath().normalize().toString()

    public suspend fun exec(
        command: List<String>,
        timeout: Duration,
    ): ExecResult = runner.run(command, timeout)

    public companion object {

        public const val MAX_CAPTURED_BYTES: Int = 8 * 1024 * 1024
    }
}

private fun isOnSearchPath(binary: String, searchPath: String?): Boolean =
    searchPath.orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .any { directory ->
            val candidate = Path.of(directory, binary)
            Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        }

internal fun interface CommandRunner {

    suspend fun run(command: List<String>, timeout: Duration): ExecResult
}

internal fun hostCommandRunner(workspace: Path): CommandRunner = CommandRunner { command, timeout ->
    runProcess(command, workingDirectory = workspace, timeout = timeout)
}
