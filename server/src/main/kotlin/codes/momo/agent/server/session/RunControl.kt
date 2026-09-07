package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.server.cut.retryPlan
import codes.momo.agent.server.storage.UnknownSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun SessionRegistry.startRun(id: String, prompt: String, settings: RunSettings) {
    val tree = treeOf(id)
    tree.root.mutex.withLock {
        launchRun(tree) { agent -> agent.send(prompt, settings) }
    }
}

internal suspend fun SessionRegistry.retryRun(id: String) {
    val tree = treeOf(id)
    changes.announcing {
        tree.root.mutex.withLock {
            tree.requireNoRunInFlight()
            val plan = retryPlan(withContext(Dispatchers.IO) { store.readEvents(id) })
            cutTree(tree, plan.lastSurvivingSequenceId)
            launchRun(tree) { agent -> agent.retry(plan.settings) }
        }
    }
}

internal suspend fun SessionRegistry.stopRun(id: String) {
    val tree = treeOf(id)
    tree.root.runtime?.stopRun(tree.path)
}

private suspend fun SessionRegistry.launchRun(tree: SessionTree, run: suspend (Agent) -> RunResult) {
    val root = tree.root
    val attached = root.runtime
    val runtime = attached ?: rebuildTreeRuntime(root, tree.rootId).also { root.runtime = it }
    try {
        val agent = runtime.agentAt(tree.path) ?: throw UnknownSessionException(tree.id)
        runtime.launchRun(agent, run)
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        if (attached == null) {
            root.detachRuntime()
        }
        throw failure
    }
}
