package codes.momo.agent.server.script

import codes.momo.agent.server.change.ChangeSignal
import codes.momo.agent.server.storage.ScriptStore
import codes.momo.agent.server.storage.StoredScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class ScriptInfo(
    val id: String,
    val title: String,
    val state: ScriptState,
    val codePath: String,
    val logPath: String,
)

@Serializable
internal enum class ScriptState {

    @SerialName("inactive")
    INACTIVE,

    @SerialName("running")
    RUNNING,

    /** Inactive after a run that ended on its own with a non-zero exit code. */
    @SerialName("failed")
    FAILED,
}

internal class ScriptRunningException(title: String) : RuntimeException("The script '$title' is running.")

/** Every script of every workspace; a script of another workspace than the one named is unknown. */
internal class ScriptRegistry(dataDir: Path, serverUrl: String, private val changes: ChangeSignal) {

    private val store = ScriptStore(dataDir)

    private val launcher = ScriptLauncher(dataDir, serverUrl)

    private val lastRuns = ConcurrentHashMap<String, ScriptRun>()

    private val mutex = Mutex()

    suspend fun list(workspace: String): List<ScriptInfo> = serialized {
        val running = lastRuns.values.filter { it.state == ScriptState.RUNNING }.map { it.script }
        (running + store.all()).distinctBy { it.id }
            .filter { it.workspace == workspace }
            .map { it.info(lastRuns[it.id]) }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun create(workspace: String, title: String): ScriptInfo = changing {
        store.create(workspace, title).info(lastRun = null)
    }

    suspend fun rename(workspace: String, id: String, title: String): ScriptInfo = changing {
        store.rename(inactive(workspace, id), title).info(lastRuns[id])
    }

    suspend fun delete(workspace: String, id: String): Unit = changing {
        store.delete(inactive(workspace, id))
        lastRuns.remove(id)
    }

    suspend fun start(workspace: String, id: String): ScriptInfo = changing {
        val script = inactive(workspace, id)
        val run = launcher.launch(script, onExit = changes::announce)
        lastRuns[id] = run
        script.info(run)
    }

    /** Answers once the script's process is gone. */
    suspend fun stop(workspace: String, id: String): ScriptInfo = changing {
        val script = known(workspace, id)
        lastRuns[id]?.stop()
        script.info(lastRuns[id])
    }

    private fun inactive(workspace: String, id: String): StoredScript {
        val script = known(workspace, id)
        if (lastRuns[id]?.state == ScriptState.RUNNING) {
            throw ScriptRunningException(script.title)
        }
        return script
    }

    /** A running script is known as it was started, so it can be stopped whatever became of its folder. */
    private fun known(workspace: String, id: String): StoredScript {
        val running = lastRuns[id]?.takeIf { it.state == ScriptState.RUNNING }?.script
        return running?.takeIf { it.workspace == workspace } ?: store.read(workspace, id)
    }

    private suspend fun <T> changing(change: suspend () -> T): T = changes.announcing { serialized(change) }

    private suspend fun <T> serialized(action: suspend () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { action() } }
}

private fun StoredScript.info(lastRun: ScriptRun?): ScriptInfo = ScriptInfo(
    id = id,
    title = title,
    state = lastRun?.state ?: ScriptState.INACTIVE,
    codePath = codeFile.toString(),
    logPath = logFile.toString(),
)
