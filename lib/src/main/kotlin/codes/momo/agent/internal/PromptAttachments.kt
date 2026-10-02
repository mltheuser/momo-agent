package codes.momo.agent.internal

import codes.momo.agent.AgentEvent
import codes.momo.agent.content.ModelContent
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.image.LoadedImage
import codes.momo.agent.image.loadImage
import kotlinx.coroutines.CancellationException

internal val MARKDOWN_IMAGE: Regex = Regex("""!\[[^\]]*]\(([^()\s]+)\)""")

internal suspend fun resolvePromptAttachments(
    prompt: String,
    environment: ExecutionEnvironment,
): List<AgentEvent.RunStarted.Attachment> =
    MARKDOWN_IMAGE.findAll(prompt).map { it.groupValues[1] }.distinct().toList()
        .mapNotNull { link ->
            fittingImage(link, environment)?.let { AgentEvent.RunStarted.Attachment(link, it.mimeType, it.base64Data) }
        }

private suspend fun fittingImage(link: String, environment: ExecutionEnvironment): ModelContent.Media? = try {
    val loaded = loadImage(link, environment.workspacePath, MAX_ATTACHMENT_CHARS) as? LoadedImage.Loaded
    loaded?.image?.takeIf { it.fitsIn(MAX_ATTACHMENT_CHARS) }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    null
}

private const val MAX_ATTACHMENT_BYTES: Int = 5 * 1024 * 1024

private val MAX_ATTACHMENT_CHARS: Int = ModelContent.Media.charCountOf(MAX_ATTACHMENT_BYTES)
