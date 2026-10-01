package codes.momo.agent.tool

import ai.router.sdk.AiRouterClient
import ai.router.sdk.contents.ContentsRequest
import ai.router.sdk.contents.ContentsResponse
import ai.router.sdk.schema.Description
import ai.router.sdk.search.SearchRequest
import ai.router.sdk.search.SearchResponse
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        return ToolResult.Success(Json.encodeToString(SearchResponse.serializer(), response))
    }
}

@Serializable
internal data class PageContentsArgs(
    @Description("Absolute http(s) URLs of the pages to load.")
    val urls: List<String>,
)

internal class PageContentsTool(private val client: AiRouterClient) : Tool<PageContentsArgs>(
    name = "page_contents",
    description = PAGE_CONTENTS_DESCRIPTION,
    argsSerializer = PageContentsArgs.serializer(),
) {

    override suspend fun execute(args: PageContentsArgs, environment: ExecutionEnvironment): ToolResult {
        val response = client.contents.send(ContentsRequest(CONTENTS_MODEL, args.urls))
        return ToolResult.Success(Json.encodeToString(ContentsResponse.serializer(), response))
    }
}

private const val SEARCH_MODEL: String = "auto:cloud@exa"

private const val CONTENTS_MODEL: String = "auto:cloud@exa"

private val WEB_SEARCH_DESCRIPTION: String = """
    Searches the web. Returns the search's JSON response: results ordered most relevant first, each
    with the page's url, title (may be empty) and snippet, the excerpts of the page that match the
    query. Results beyond ${ToolRegistry.MAX_RESULT_CHARS} characters are cut off at the end.
""".trimIndent()

private val PAGE_CONTENTS_DESCRIPTION: String = """
    Loads web pages. Returns the JSON response: one result per requested URL, in request order, each
    with the url as requested, the page's title and its text as markdown. A page that could not be
    loaded has an error instead of title and text; the other pages are unaffected. All pages share
    one budget of ${ToolRegistry.MAX_RESULT_CHARS} characters, cut off at the end, so request fewer
    pages per call when the later ones matter.
""".trimIndent()
