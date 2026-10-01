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
    Loads web pages through a content extraction service and saves each as a text file under $PAGE_DIR.
    Prefer it over curl for reading web pages: it renders JavaScript, extracts PDFs and complex layouts,
    and drops navigation clutter. Use curl for raw files, JSON APIs and exact bytes.

    Returns JSON with one result per requested URL, in request order: the file's path, the page's title,
    its size in bytes and lines, and an outline of the headings found in the text with their line
    numbers (may be empty; capped, with an outline_note saying what was left out). Read the file with
    bash (grep, sed -n, head, python) rather than printing it whole. A page that could not be loaded has
    an error instead; the other pages are unaffected.

    A file never changes once written: loading a page again saves a new file if the page changed. Files
    may disappear, e.g. on reboot; if a path is gone, load the page again. Pages may include image links
    inline, e.g. `![alt](url)`; to look at one, pass its absolute URL to view_image.
""".trimIndent()
