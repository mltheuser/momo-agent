package codes.momo.agent.internal

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import codes.momo.agent.AgentEvent

internal fun systemMessage(instructions: String): ChatMessage = textMessage(ROLE_SYSTEM, instructions)

private fun textMessage(role: String, text: String): ChatMessage =
    ChatMessage(role = role, content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)))

internal fun userMessage(
    text: String,
    attachments: List<AgentEvent.RunStarted.Attachment> = emptyList(),
): ChatMessage {
    val imageByLink = attachments.associateBy { it.link }
    if (imageByLink.isEmpty()) {
        return textMessage(ROLE_USER, text)
    }
    val parts = buildList {
        var consumed = 0
        for (match in MARKDOWN_IMAGE.findAll(text)) {
            val image = imageByLink[match.groupValues[1]] ?: continue
            add(ContentPart(type = ContentPartType.TEXT, text = text.substring(consumed, match.range.last + 1)))
            add(ContentPart(type = ContentPartType.IMAGE, mimeType = image.mimeType, base64Data = image.base64Data))
            consumed = match.range.last + 1
        }
        if (consumed < text.length) {
            add(ContentPart(type = ContentPartType.TEXT, text = text.substring(consumed)))
        }
    }
    return ChatMessage(role = ROLE_USER, content = parts)
}

internal fun toolResultMessage(
    callId: String,
    text: String,
    media: AgentEvent.ToolCallFinished.Media? = null,
): ChatMessage =
    ChatMessage(
        role = ROLE_TOOL,
        content = when (media) {
            null -> listOf(ContentPart(type = ContentPartType.TEXT, text = text))
            else -> listOf(
                ContentPart(type = ContentPartType.IMAGE, mimeType = media.mimeType, base64Data = media.base64Data),
            )
        },
        toolCallId = callId,
    )

internal val ChatMessage.awaitsModel: Boolean
    get() = role == ROLE_USER || role == ROLE_TOOL

private const val ROLE_SYSTEM: String = "system"
private const val ROLE_USER: String = "user"
private const val ROLE_TOOL: String = "tool"
