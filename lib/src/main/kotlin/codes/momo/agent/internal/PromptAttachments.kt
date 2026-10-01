package codes.momo.agent.internal

import codes.momo.agent.AgentEvent
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.image.LoadedImage
import codes.momo.agent.image.MAX_IMAGE_BYTES
import codes.momo.agent.image.loadImage
import kotlinx.coroutines.CancellationException
import java.util.Base64

internal val MARKDOWN_IMAGE: Regex = Regex("""!\[[^\]]*]\(([^()\s]+)\)""")

internal suspend fun resolvePromptAttachments(
    prompt: String,
    environment: ExecutionEnvironment,
): List<AgentEvent.RunStarted.Attachment> =
    MARKDOWN_IMAGE.findAll(prompt).map { it.groupValues[1] }.distinct().toList()
        .mapNotNull { link ->
            loadedWithinLimit(link, environment)?.let { image ->
                AgentEvent.RunStarted.Attachment(link, image.mimeType, Base64.getEncoder().encodeToString(image.bytes))
            }
        }

private suspend fun loadedWithinLimit(link: String, environment: ExecutionEnvironment): LoadedImage.Loaded? = try {
    (loadImage(link, environment.workspacePath, readLimitBytes = MAX_IMAGE_BYTES + 1) as? LoadedImage.Loaded)
        ?.takeIf { it.bytes.size <= MAX_IMAGE_BYTES }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    null
}
