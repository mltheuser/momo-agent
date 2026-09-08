package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.server.cut.retryPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun SessionRegistry.startRun(id: String, prompt: String, settings: RunSettings) {
    val tree = settledTreeOf(id)
    tree.root.mutex.withLock {
        launchRun(tree) { agent -> agent.send(prompt, settings) }
    }
}

internal suspend fun SessionRegistry.retryRun(id: String) {
    val tree = settledTreeOf(id)
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
    val tree = settledTreeOf(id)
    tree.root.run?.loadedAgentAt(tree.path)?.stop()
}

private suspend fun SessionRegistry.launchRun(tree: SessionTree, run: suspend (Agent) -> RunResult) {
    tree.requireNoRunInFlight()
    val active = withContext(Dispatchers.IO) { loadRun(tree) }
    tree.root.run = active
    changes.announce()
    active.launch {
        try {
            run(active.agent)
        } finally {
            active.closeLogs()
            tree.root.run = null
            changes.announce()
        }
    }
}
