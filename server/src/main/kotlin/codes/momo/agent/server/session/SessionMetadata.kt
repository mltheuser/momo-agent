package codes.momo.agent.server.session

import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import codes.momo.agent.SelectionPatch
import codes.momo.agent.server.storage.EventLogFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun SessionRegistry.rename(id: String, title: String): SessionSummary = recordMetadata(
    id,
    onAgent = { it.title = title },
    storedEvent = { sequenceId, at -> AgentEvent.SessionRenamed(sequenceId, at, title) },
)

internal suspend fun SessionRegistry.select(id: String, patch: SelectionPatch): SessionSummary = recordMetadata(
    id,
    onAgent = { it.recordSelection(patch) },
    storedEvent = { sequenceId, at -> AgentEvent.SelectionChanged(sequenceId, at, patch) },
)

private suspend fun SessionRegistry.recordMetadata(
    id: String,
    onAgent: (Agent) -> Unit,
    storedEvent: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
): SessionSummary {
    val tree = settledTreeOf(id)
    changes.announcing {
        tree.root.mutex.withLock {
            val agent = tree.root.run?.agentAt(tree.path)
            if (agent == null) {
                appendToStoredLog(id, storedEvent)
            } else {
                withContext(Dispatchers.IO) { onAgent(agent) }
            }
        }
    }
    return summary(id)
}

private suspend fun SessionRegistry.appendToStoredLog(
    id: String,
    event: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
) = withContext(Dispatchers.IO) {
    val nextSequenceId = store.readEvents(id).last().sequenceId + 1
    val stamped = event(nextSequenceId, System.currentTimeMillis())
    try {
        store.writer(id).use { it.onEvent(stamped) }
    } catch (failure: IOException) {
        throw EventLogFailedException(failure)
    }
}
