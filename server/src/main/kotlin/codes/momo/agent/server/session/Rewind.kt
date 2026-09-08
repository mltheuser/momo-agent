package codes.momo.agent.server.session

import codes.momo.agent.AgentEvent
import codes.momo.agent.answerCallsCutByRewind
import codes.momo.agent.server.cut.RewindPlan
import codes.momo.agent.server.cut.lastSurvivorOfCutFrom
import codes.momo.agent.server.cut.rewindPlan
import codes.momo.agent.server.cut.survivesCut
import codes.momo.agent.server.storage.encodeLogLine
import codes.momo.agent.server.storage.readEventsOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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
        applyPlan(rewindPlan(tree.id, lastSurvivingSequenceId, store::readEventsOrNull))
    }

private fun SessionRegistry.applyPlan(plan: RewindPlan): List<String> {
    val deleted = plan.deletedSubtreeRoots.flatMap { removeSubtree(it) }
    val gone = deleted.toSet()
    plan.cuts.reversed().forEach { (sessionId, lastSurviving) ->
        if (sessionId !in gone) {
            cutLog(sessionId, lastSurviving)
        }
    }
    return deleted
}

private fun SessionRegistry.cutLog(sessionId: String, lastSurvivingSequenceId: Long) {
    val lines = store.readLines(sessionId)
    val surviving = lines.filter { it.survivesCut(lastSurvivingSequenceId) }
    val now = System.currentTimeMillis()
    val answers = answerCallsCutByRewind(
        surviving = surviving.map { Json.decodeFromString<AgentEvent>(it.json) },
        firstSequenceId = lines.last().sequenceId + 1,
        timestampMillis = now,
    )
    val rewound = AgentEvent.ConversationRewound(
        sequenceId = lines.last().sequenceId + 1 + answers.size,
        timestampMillis = now,
        lastSurvivingSequenceId = lastSurvivingSequenceId,
    )
    store.rewriteLog(sessionId, surviving.map { it.json } + (answers + rewound).map(::encodeLogLine))
    entryOrNull(sessionId)?.log?.cut(rewound.sequenceId)
}
