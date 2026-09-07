package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.Capability
import ai.router.sdk.models.ModelList

public suspend fun AiRouterClient.usableModels(): ModelList {
    val catalog = listModels(capability = Capability.CHAT)
    return catalog.copy(data = catalog.data.filter { it.hasCapability(Capability.TOOLS) })
}
