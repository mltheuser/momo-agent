package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.Agent
import codes.momo.agent.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun SessionRegistry.rename(id: String, title: String): SessionInfo = recordMetadata(
    id,
    onAgent = { it.title = title },
    dormantEvent = { sequenceId, at -> AgentEvent.SessionRenamed(sequenceId, at, title) },
)

internal suspend fun SessionRegistry.selectModel(
    id: String,
    model: String,
    reasoningEffort: ReasoningEffort?,
): SessionInfo = recordMetadata(
    id,
    onAgent = { it.recordModelSelection(model, reasoningEffort) },
    dormantEvent = { sequenceId, at -> AgentEvent.ModelSelected(sequenceId, at, model, reasoningEffort) },
)

private suspend fun SessionRegistry.recordMetadata(
    id: String,
    onAgent: (Agent) -> Unit,
    dormantEvent: (sequenceId: Long, timestampMillis: Long) -> AgentEvent,
): SessionInfo {
    val tree = treeOf(id)
    changes.announcing {
        tree.root.mutex.withLock {
            val runtime = tree.root.runtime
            if (runtime == null) {
                appendToDormantLog(id, dormantEvent)
            } else {
                val agent = runtime.agentAt(tree.path) ?: throw UnknownSessionException(id)
                withContext(Dispatchers.IO) { onAgent(agent) }
            }
        }
    }
    return info(id)
}

private suspend fun SessionRegistry.appendToDormantLog(
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
    entry(id).eventSignal.value = stamped.sequenceId
}
