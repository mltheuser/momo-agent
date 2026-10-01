package codes.momo.agent.web

import ai.router.sdk.AiRouterClient
import ai.router.sdk.schema.Description
import ai.router.sdk.search.SearchRequest
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.tool.Tool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WebSearchArgs(
    @Description("What to search the web for.")
    val query: String,
    @SerialName("max_results")
    @Description("The most results to return; the search may return fewer. Omit for the search's default.")
    val maxResults: Int? = null,
)

internal class WebSearchTool(private val client: AiRouterClient) : Tool<WebSearchArgs>(
    name = "web_search",
    description = WEB_SEARCH_DESCRIPTION,
    argsSerializer = WebSearchArgs.serializer(),
) {

    override suspend fun execute(args: WebSearchArgs, environment: ExecutionEnvironment): ToolResult {
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
    Searches the web. Returns JSON: results ordered most relevant first, each with the page's url,
    title (may be empty) and snippet, excerpts of the page that match the query, sized across the
    whole result set. Results beyond ${ToolRegistry.MAX_RESULT_CHARS} characters are cut off at the end.
""".trimIndent()
