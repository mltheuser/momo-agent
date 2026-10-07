package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.content.ModelContent
import codes.momo.agent.image.LoadedImage
import codes.momo.agent.image.loadImage
import kotlinx.serialization.Serializable

@Serializable
internal data class ViewImageArgs(
    @Description("A local path (absolute, workspace-relative or starting with ~) or an absolute http(s) URL.")
    val source: String,
)

internal class ViewImageTool(spec: ToolSpec) : Tool<ViewImageArgs>(
    spec = spec,
    description = VIEW_IMAGE_DESCRIPTION,
    argsSerializer = ViewImageArgs.serializer(),
) {

    override val maxResultChars: Int = MAX_RESULT_CHARS

    override suspend fun execute(args: ViewImageArgs, context: ToolContext): ToolResult =
        when (val loaded = loadImage(args.source, context.environment.workspacePath, maxResultChars)) {
            is LoadedImage.Failed -> ToolResult.Error("cannot view '${args.source}': ${loaded.problem}.")
            is LoadedImage.Loaded -> ToolResult.Success(loaded.image)
        }
}

private const val MAX_IMAGE_BYTES: Int = 5 * 1024 * 1024

private val MAX_RESULT_CHARS: Int = ModelContent.Media.charCountOf(MAX_IMAGE_BYTES)

private val VIEW_IMAGE_DESCRIPTION: String = """
    Shows you an image: a local file or an image on the web. A URL must lead to the image file itself; a web
    page is not an image. An image over ${ModelContent.Media.maxBytesWithin(MAX_RESULT_CHARS)} bytes is refused.
""".trimIndent()
