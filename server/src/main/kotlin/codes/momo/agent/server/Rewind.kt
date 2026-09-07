package codes.momo.agent.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun SessionRegistry.rewind(id: String, firstDeletedSequenceId: Long): List<String> {
    val tree = treeOf(id)
    return changes.announcing {
        tree.root.mutex.withLock {
            tree.requireNoRunInFlight()
            withContext(Dispatchers.IO) {
                val lastSurviving = store.readEvents(id).lastSurvivorOfCutFrom(id, firstDeletedSequenceId)
                cutTree(tree, lastSurviving)
            }
        }
    }
}

internal suspend fun SessionRegistry.cutTree(tree: SessionTree, lastSurvivingSequenceId: Long): List<String> =
    withContext(NonCancellable + Dispatchers.IO) {
        val plan = rewindPlan(tree.id, lastSurvivingSequenceId, store::readEventsOrNull)
        val detached = tree.root.detachRuntime()
        if (detached == null) {
            applyPlan(plan)
        } else {
            val harness = store.readSessionStarted(tree.rootId).loadHarness()
            val deleted = applyPlan(plan)
            runCatching { tree.root.runtime = loadTreeRuntime(tree.root, tree.rootId, harness, detached.environment) }
            deleted
        }
    }

private fun SessionRegistry.applyPlan(plan: RewindPlan): List<String> {
    val deleted = plan.deletedSubtreeRoots.flatMap { removeSubtree(it) }
    val gone = deleted.toSet()
    plan.cuts.reversed().forEach { (sessionId, lastSurviving) ->
        if (sessionId !in gone) {
            val rewound = store.rewindEvents(sessionId, lastSurviving)
            entryOrNull(sessionId)?.log?.cut(rewound.sequenceId)
        }
    }
    return deleted
}
