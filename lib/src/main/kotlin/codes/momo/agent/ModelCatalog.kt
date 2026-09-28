package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatFeature
import ai.router.sdk.models.ChatModel
import ai.router.sdk.models.ModelList

public suspend fun AiRouterClient.usableChatModels(): ModelList<ChatModel> {
    val catalog = listChatModels()
    return catalog.copy(data = catalog.data.filter { it.has(ChatFeature.TOOLS) })
}
