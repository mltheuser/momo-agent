package codes.momo.agent.tool

import ai.router.sdk.chat.ChatMessage
import ai.router.sdk.chat.ChatRole
import ai.router.sdk.chat.ChatSettings
import ai.router.sdk.chat.ToolCall
import codes.momo.agent.RunSettings
import codes.momo.agent.content.ModelContent
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.internal.toolResultMessage

internal class ToolContext(
    val environment: ExecutionEnvironment,
    val settings: RunSettings,
    val chatSettings: ChatSettings,
    private val conversation: List<ChatMessage>,
    private val call: ToolCall,
) {

    /** The run's model for [spec]; a run starts only when its settings cover every tool taking a model. */
    fun modelFor(spec: ToolSpec): String = settings.toolModels.getValue(spec.name)

    /**
     * The conversation the run's next LLM call would send with [result] answering this call. The turn's later
     * calls are left out: they have no result yet, and a request with an unanswered call is refused.
     */
    fun conversationAnsweredWith(result: ModelContent): List<ChatMessage> {
        val turn = conversation.indexOfLast { it.role == ChatRole.ASSISTANT }
        val asked = conversation[turn]
        val upToThisCall = asked.copy(toolCalls = asked.toolCalls.take(asked.toolCalls.indexOf(call) + 1))
        return conversation.take(turn) + upToThisCall + conversation.drop(turn + 1) + toolResultMessage(call.id, result)
    }
}
