package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.problem
import codes.momo.agent.environment.succeeded
import kotlinx.serialization.Serializable
import java.net.URLConnection

@Serializable
public data class ViewImageArgs(
    @Description("Path of the image file to view: absolute or workspace-relative.")
    val path: String,
)

public class ViewImageTool : Tool<ViewImageArgs>(
    name = NAME,
    description = VIEW_IMAGE_DESCRIPTION,
    argsSerializer = ViewImageArgs.serializer(),
) {

    override suspend fun execute(args: ViewImageArgs, environment: ExecutionEnvironment): ToolResult =
        loadImage(args.path, environment)

    internal companion object {

        const val NAME: String = "view_image"
    }
}

internal const val MAX_IMAGE_BYTES: Long = 5L * 1024 * 1024

internal suspend fun loadImage(path: String, environment: ExecutionEnvironment): ToolResult {
    val measured = environment.exec(pathCommand("wc -c < \"\$p\"", path), timeout = Budgets.TOOL_TIMEOUT)
    val bytes = measured.stdout.trim().toLongOrNull()
    return when {
        measured is ExecResult.TimedOut -> ToolResult.TimedOut()
        !measured.succeeded || bytes == null -> ToolResult.Error("cannot read '$path': ${measured.problem()}")
        bytes > MAX_IMAGE_BYTES -> ToolResult.Error(
            "'$path' is $bytes bytes, over the $MAX_IMAGE_BYTES-byte limit for images. " +
                "Downscale or re-encode it with command-line tools and view the smaller file.",
        )

        else -> encode(path, environment)
    }
}

private suspend fun encode(path: String, environment: ExecutionEnvironment): ToolResult {
    val encoded = environment.exec(pathCommand("base64 < \"\$p\"", path), timeout = Budgets.TOOL_TIMEOUT)
    return when {
        encoded is ExecResult.TimedOut -> ToolResult.TimedOut()
        !encoded.succeeded -> ToolResult.Error("cannot read '$path': ${encoded.problem()}")
        encoded.stdoutTruncated ->
            ToolResult.Error("cannot read '$path': it outgrew its measured size while being encoded.")

        else -> ToolResult.Image(imageMimeType(path), encoded.stdout.filterNot { it.isWhitespace() })
    }
}

private fun pathCommand(script: String, path: String): List<String> =
    listOf("bash", "-c", "p=\"\${1/#\\~/\$HOME}\"; $script", "view_image", path)

internal fun imageMimeType(path: String): String =
    URLConnection.guessContentTypeFromName(path)?.takeIf { it.startsWith("image/") } ?: DEFAULT_IMAGE_MIME

internal const val DEFAULT_IMAGE_MIME: String = "image/png"

private val VIEW_IMAGE_DESCRIPTION: String = """
    Loads an image file and presents it to the model visually. A file over $MAX_IMAGE_BYTES bytes is refused.
""".trimIndent()
