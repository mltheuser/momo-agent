package codes.momo.agent.tool

import ai.router.sdk.AiRouterClient
import ai.router.sdk.ModelList
import ai.router.sdk.ModelRef
import ai.router.sdk.contents.ContentsModel
import ai.router.sdk.search.SearchModel
import kotlinx.serialization.KSerializer

/** One router use case whose models a tool runs on. [serializer] encodes its typed list, as [ModelRef] cannot be. */
public sealed interface ToolModelSource<M : ModelRef> {

    public val serializer: KSerializer<ModelList<M>>

    public suspend fun models(client: AiRouterClient): ModelList<M>

    public data object Search : ToolModelSource<SearchModel> {

        override val serializer: KSerializer<ModelList<SearchModel>> = ModelList.serializer(SearchModel.serializer())

        override suspend fun models(client: AiRouterClient): ModelList<SearchModel> = client.search.listModels()
    }

    public data object Contents : ToolModelSource<ContentsModel> {

        override val serializer: KSerializer<ModelList<ContentsModel>> =
            ModelList.serializer(ContentsModel.serializer())

        override suspend fun models(client: AiRouterClient): ModelList<ContentsModel> = client.contents.listModels()
    }
}
