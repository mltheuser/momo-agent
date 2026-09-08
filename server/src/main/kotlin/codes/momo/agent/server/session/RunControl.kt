package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.server.cut.retryPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
            runCatching { active.closeLogs() }.onFailure { logger.error("Closing ${tree.id}'s event log failed.", it) }
            tree.root.run = null
            changes.announce()
        }
    }
}

private val logger: Logger = LoggerFactory.getLogger("codes.momo.agent.server.session.RunControl")
