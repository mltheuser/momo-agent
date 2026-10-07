package codes.momo.agent.web

import ai.router.sdk.AiRouterClient
import ai.router.sdk.schema.Description
import ai.router.sdk.search.SearchRequest
import codes.momo.agent.tool.Tool
import codes.momo.agent.tool.ToolContext
import codes.momo.agent.tool.ToolResult
import codes.momo.agent.tool.ToolSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WebSearchArgs(
    @Description("Natural-language search query. Long, semantically rich descriptions work well.")
    val query: String,
    @SerialName("max_results")
    @Description("Number of results to return. Use small values for agent loops.")
    val maxResults: Int? = null,
)

internal class WebSearchTool(spec: ToolSpec, private val client: AiRouterClient) : Tool<WebSearchArgs>(
    spec = spec,
    description = WEB_SEARCH_DESCRIPTION,
    argsSerializer = WebSearchArgs.serializer(),
) {

    override suspend fun execute(args: WebSearchArgs, context: ToolContext): ToolResult {
        val response = client.search.send(SearchRequest(SEARCH_MODEL, args.query, args.maxResults))
        val results = response.results.map { SearchResult(it.url, it.title, it.snippet) }
        return ToolResult.Success(webToolJson.encodeToString(SearchResults.serializer(), SearchResults(results)))
    }
}

@Serializable
private data class SearchResults(val results: List<SearchResult>)

@Serializable
private data class SearchResult(val url: String, val title: String, val snippet: String)

private const val SEARCH_MODEL: String = "fast:cloud@exa"

private val WEB_SEARCH_DESCRIPTION: String = """
    Searches the web. Returns results ordered by relevance.
""".trimIndent()
