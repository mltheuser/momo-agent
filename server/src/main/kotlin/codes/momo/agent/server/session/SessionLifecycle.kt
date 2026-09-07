package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.server.storage.EventLogFailedException
import codes.momo.agent.server.storage.pathTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal suspend fun SessionRegistry.create(harnessPath: String, workspace: String, title: String?): SessionInfo =
    changes.announcing {
        withContext(Dispatchers.IO) {
            val harnessFolder = Path.of(harnessPath)
            val harness = Harness.load(harnessFolder)
            val environment = ExecutionEnvironment(Path.of(workspace))
            val root = SessionEntry()
            val log = store.writer()
            val runtime = buildTreeRuntime(root, environment, log) { listener ->
                Agent(harness, client, environment, title ?: harnessFolder.fileName.toString(), listener)
            }
            log.failure?.let { failure ->
                runCatching { log.close() }
                runCatching { store.delete(runtime.rootId) }
                throw EventLogFailedException(failure)
            }
            root.runtime = runtime
            register(runtime.rootId, root)
            info(runtime.rootId)
        }
    }

internal suspend fun SessionRegistry.closeSession(id: String) {
    val tree = treeOf(id)
    changes.announcing {
        tree.root.mutex.withLock { tree.root.detachRuntime() }
    }
}

internal suspend fun SessionRegistry.delete(id: String) {
    val target = entry(id)
    val root = withContext(Dispatchers.IO) {
        try {
            entry(store.pathTo(id).first())
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            target
        }
    }
    changes.announcing {
        root.mutex.withLock {
            root.detachRuntime()
            withContext(NonCancellable + Dispatchers.IO) {
                removeSubtree(id)
            }
        }
    }
}

internal fun SessionRegistry.shutdown() {
    runBlocking {
        ids.forEach { id ->
            runCatching { closeSession(id) }
        }
    }
}
