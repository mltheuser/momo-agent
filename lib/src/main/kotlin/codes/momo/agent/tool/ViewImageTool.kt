package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.image.LoadedImage
import codes.momo.agent.image.MAX_IMAGE_BYTES
import codes.momo.agent.image.base64EncodedLength
import codes.momo.agent.image.loadImage
import codes.momo.agent.image.maxBytesWhoseBase64FitsIn
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
internal data class ViewImageArgs(
    @Description("A local path (absolute, workspace-relative or starting with ~) or an absolute http(s) URL.")
    val source: String,
)

internal class ViewImageTool : Tool<ViewImageArgs>(
    name = NAME,
    description = VIEW_IMAGE_DESCRIPTION,
    argsSerializer = ViewImageArgs.serializer(),
) {

    override val maxResultChars: Int = RESULT_LIMIT_CHARS

    override suspend fun execute(args: ViewImageArgs, environment: ExecutionEnvironment): ToolResult =
        when (val image = loadImage(args.source, environment.workspacePath, MAX_VIEWABLE_BYTES + 1)) {
            is LoadedImage.Failed -> ToolResult.Error("cannot view '${args.source}': ${image.problem}.")
            is LoadedImage.Loaded -> ToolResult.Image(image.mimeType, Base64.getEncoder().encodeToString(image.bytes))
        }

    companion object {

        const val NAME: String = "view_image"
    }
}

private val RESULT_LIMIT_CHARS: Int = base64EncodedLength(MAX_IMAGE_BYTES)

private val MAX_VIEWABLE_BYTES: Int = maxBytesWhoseBase64FitsIn(RESULT_LIMIT_CHARS)

private val VIEW_IMAGE_DESCRIPTION: String = """
    Shows you an image: a local file or an image on the web. A URL must lead to the image file itself; a web
    page is not an image. An image over $MAX_VIEWABLE_BYTES bytes is refused.
""".trimIndent()
