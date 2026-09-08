package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.server.storage.EventLogFailedException
import codes.momo.agent.server.storage.ifReadable
import codes.momo.agent.server.storage.pathTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal suspend fun SessionRegistry.create(harnessPath: String, workspace: String, title: String?): SessionInfo =
    changes.announcing {
        withContext(Dispatchers.IO) {
            val harnessFolder = Path.of(harnessPath)
            val harness = Harness.load(harnessFolder)
            val environment = ExecutionEnvironment(Path.of(workspace))
            val root = SessionEntry()
            val log = store.writer()
            val listener = TreeMemberListener(this@create, ConcurrentHashMap(), root, log)
            val agent = Agent(harness, client, environment, title ?: harnessFolder.fileName.toString(), listener)
            try {
                log.close()
            } catch (failure: IOException) {
                runCatching { store.delete(agent.sessionId) }
                throw EventLogFailedException(failure)
            }
            register(agent.sessionId, root)
            info(agent.sessionId)
        }
    }

internal suspend fun SessionRegistry.delete(id: String) {
    val target = entry(id)
    val rootId = withContext(Dispatchers.IO) { store.ifReadable { pathTo(readSessionStarted(id)).first() } }
    val root = rootId?.let(::entryOrNull) ?: target
    changes.announcing {
        root.mutex.withLock {
            root.run?.abort()
            withContext(NonCancellable + Dispatchers.IO) {
                removeSubtree(id)
            }
        }
    }
}

internal fun SessionRegistry.shutdown() {
    runBlocking {
        ids.mapNotNull { entryOrNull(it)?.run }.forEach { run -> runCatching { run.abort() } }
    }
}
