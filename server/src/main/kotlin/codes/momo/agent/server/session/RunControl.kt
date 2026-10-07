package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.harness.Harness
import codes.momo.agent.server.cut.retryPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

// Each refusal (a run in flight, a broken harness, a missing tool model, nothing to retry) comes before the
// first mutation, so a refused request leaves the log as it was. A retry replays settings its run started
// with, so they cover the harness already.

internal suspend fun SessionRegistry.startRun(id: String, prompt: String, settings: RunSettings) {
    val tree = settledTreeOf(id)
    tree.root.mutex.withLock {
        tree.requireNoRunInFlight()
        val harness = loadHarness(tree)
        harness.requireToolModels(settings)
        launchRun(tree, harness) { agent -> agent.send(prompt, settings) }
    }
}

internal suspend fun SessionRegistry.retryRun(id: String) {
    val tree = settledTreeOf(id)
    changes.announcing {
        tree.root.mutex.withLock {
            tree.requireNoRunInFlight()
            val harness = loadHarness(tree)
            val plan = retryPlan(withContext(Dispatchers.IO) { store.readEvents(id) })
            cutTree(tree, plan.lastSurvivingSequenceId)
            launchRun(tree, harness) { agent -> agent.retry(plan.settings) }
        }
    }
}

internal suspend fun SessionRegistry.stopRun(id: String) {
    val tree = settledTreeOf(id)
    tree.root.run?.loadedAgentAt(tree.path)?.stop()
}

private suspend fun SessionRegistry.loadHarness(tree: SessionTree): Harness = withContext(Dispatchers.IO) {
    Harness.load(Path.of(store.readSessionStarted(tree.id).harnessFolder))
}

private suspend fun SessionRegistry.launchRun(
    tree: SessionTree,
    harness: Harness,
    run: suspend (Agent) -> RunResult,
) {
    val active = withContext(Dispatchers.IO) { loadRun(tree, harness) }
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
