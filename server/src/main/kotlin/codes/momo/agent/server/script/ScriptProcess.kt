package codes.momo.agent.server.script

import codes.momo.agent.server.storage.StoredScript
import kotlinx.coroutines.future.await
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

internal class ScriptStartException(cause: IOException) :
    RuntimeException("The script could not be started: ${cause.message}", cause)

/** One start of [script]. Its state is read off the process, so nothing has to be told when the process ends. */
internal class ScriptRun(val script: StoredScript, private val process: Process) {

    @Volatile
    private var stoppedByUser = false

    val state: ScriptState
        get() = when {
            process.isAlive -> ScriptState.RUNNING
            process.exitValue() != 0 && !stoppedByUser -> ScriptState.FAILED
            else -> ScriptState.INACTIVE
        }

    suspend fun stop() {
        if (!process.isAlive) {
            return
        }
        stoppedByUser = true
        process.outputStream.close()
        process.onExit().await()
    }
}

/**
 * Starts a script through `launcher.py`, whose stdin is a pipe this server holds. The launcher kills the
 * script's process group once that pipe closes: on a stop, and on any death of the server.
 */
internal class ScriptLauncher(dataDir: Path, private val serverUrl: String) {

    private val runtimeDir: Path = dataDir.toAbsolutePath().resolve("script-runtime")

    init {
        runtimeDir.createDirectories()
        RUNTIME_FILES.forEach { name ->
            val resource = checkNotNull(javaClass.getResourceAsStream("/script-runtime/$name")) { "no resource $name" }
            runtimeDir.resolve(name).writeBytes(resource.use { it.readBytes() })
        }
    }

    fun launch(script: StoredScript, onExit: () -> Unit): ScriptRun {
        val builder = ProcessBuilder("python3", runtimeDir.resolve(LAUNCHER).toString(), script.codeFile.toString())
            .directory(script.folder.toFile())
            .redirectErrorStream(true)
            .redirectOutput(script.logFile.toFile())
        val environment = builder.environment()
        environment["MOMO_AGENT_URL"] = serverUrl
        environment["MOMO_WORKSPACE"] = script.workspace
        environment.merge("PYTHONPATH", runtimeDir.toString()) { inherited, runtime ->
            runtime + File.pathSeparator + inherited
        }
        val process = try {
            builder.start()
        } catch (failure: IOException) {
            throw ScriptStartException(failure)
        }
        process.onExit().thenRun(onExit)
        return ScriptRun(script, process)
    }
}

private const val LAUNCHER = "launcher.py"

private val RUNTIME_FILES = listOf(LAUNCHER, "momo.py")
