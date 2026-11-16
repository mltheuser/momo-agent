package codes.momo.agent.tool

import ai.router.sdk.AiRouterClient
import ai.router.sdk.AiRouterException
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

internal class ViewImageTool(spec: ToolSpec, private val client: AiRouterClient) : Tool<ViewImageArgs>(
    spec = spec,
    description = VIEW_IMAGE_DESCRIPTION,
    argsSerializer = ViewImageArgs.serializer(),
) {

    override val maxResultChars: Int = MAX_RESULT_CHARS

    override suspend fun execute(args: ViewImageArgs, context: ToolContext): ToolResult =
        when (val loaded = loadImage(args.source, context.environment.workspacePath, maxResultChars)) {
            is LoadedImage.Failed -> ToolResult.Error("cannot view '${args.source}': ${loaded.problem}.")
            is LoadedImage.Loaded -> when (val rejection = rejectionOf(loaded.image, context)) {
                null -> ToolResult.Success(loaded.image)
                else -> ToolResult.Error("cannot view '${args.source}': showing it to the model failed: $rejection")
            }
        }

    /** An image the model refuses must not become the result: every later request would carry it and fail too. */
    private suspend fun rejectionOf(image: ModelContent.Media, context: ToolContext): String? {
        val reachesTheModel = image.fitsIn(maxResultChars)
        if (!reachesTheModel) return null
        val answeringInOneToken = context.chatSettings.copy(maxTokens = 1)
        return try {
            client.chat.send(answeringInOneToken, context.conversationAnsweredWith(image))
            null
        } catch (rejection: AiRouterException) {
            rejection.apiError.message
        }
    }
}

private const val MAX_IMAGE_BYTES: Int = 5 * 1024 * 1024

private val MAX_RESULT_CHARS: Int = ModelContent.Media.charCountOf(MAX_IMAGE_BYTES)

private val VIEW_IMAGE_DESCRIPTION: String = """
    Shows you an image: a local file or an image on the web. A URL must lead to the image file itself; a web
    page is not an image. An image over ${ModelContent.Media.maxBytesWithin(MAX_RESULT_CHARS)} bytes is refused.
""".trimIndent()
