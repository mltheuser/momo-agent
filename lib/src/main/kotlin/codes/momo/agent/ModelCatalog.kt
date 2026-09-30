package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.ModelList
import ai.router.sdk.chat.ChatFeature
import ai.router.sdk.chat.ChatModel

public suspend fun AiRouterClient.usableChatModels(): ModelList<ChatModel> {
    val catalog = chat.listModels()
    return catalog.copy(data = catalog.data.filter { it.has(ChatFeature.TOOLS) })
}
