package codes.momo.agent.web

import ai.router.sdk.AiRouterClient
import ai.router.sdk.contents.ContentsRequest
import ai.router.sdk.contents.ContentsResult
import ai.router.sdk.schema.Description
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.tool.Tool
import codes.momo.agent.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PageContentsArgs(
    @Description("Absolute http(s) URLs to extract content from.")
    val urls: List<String>,
)

internal class PageContentsTool(private val client: AiRouterClient) : Tool<PageContentsArgs>(
    name = "page_contents",
    description = PAGE_CONTENTS_DESCRIPTION,
    argsSerializer = PageContentsArgs.serializer(),
) {

    override suspend fun execute(args: PageContentsArgs, environment: ExecutionEnvironment): ToolResult {
        val response = client.contents.send(ContentsRequest(CONTENTS_MODEL, args.urls))
        val pages = response.results.map { load(it) }
        return ToolResult.Success(webToolJson.encodeToString(Pages.serializer(), Pages(pages)))
    }

    private suspend fun load(result: ContentsResult): Page {
        result.error?.let { return Page(url = result.url, error = it) }
        val text = result.text.orEmpty()
        val content = text.toByteArray(Charsets.UTF_8)
        return when (val file = savePage(result.url, content)) {
            is PageFile.Failed -> Page(url = result.url, error = "loaded, but could not save the page: ${file.problem}")
            is PageFile.Saved -> {
                val outline = outline(text)
                Page(
                    url = result.url,
                    title = result.title,
                    path = file.path,
                    bytes = content.size,
                    lines = lineCount(text),
                    outline = outline.headings.map { "${it.line}: ${it.text}" },
                    outlineNote = outline.omitted,
                )
            }
        }
    }
}

/** Lines as `sed -n` numbers them: a last line without a newline counts too. */
private fun lineCount(text: String): Int =
    text.count { it == '\n' } + if (text.isEmpty() || text.endsWith('\n')) 0 else 1

@Serializable
private data class Pages(val results: List<Page>)

@Serializable
private data class Page(
    val url: String,
    val title: String? = null,
    val path: String? = null,
    val bytes: Int? = null,
    val lines: Int? = null,
    val outline: List<String>? = null,
    @SerialName("outline_note") val outlineNote: String? = null,
    val error: String? = null,
)

private const val CONTENTS_MODEL: String = "auto:cloud@exa"

private val PAGE_CONTENTS_DESCRIPTION: String = """
    Extracts content from web pages as markdown and saves each result as a text file.
    Prefer over curl: It renders JavaScript, handles complex layouts, and strips clutter.
""".trimIndent()
