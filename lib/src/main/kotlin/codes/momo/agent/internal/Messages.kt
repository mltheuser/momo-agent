package codes.momo.agent.internal

import ai.router.sdk.chat.ChatMessage
import ai.router.sdk.chat.ChatRole
import ai.router.sdk.chat.ContentPart
import codes.momo.agent.AgentEvent

internal fun systemMessage(instructions: String): ChatMessage = textMessage(ChatRole.SYSTEM, instructions)

private fun textMessage(role: ChatRole, text: String): ChatMessage =
    ChatMessage(role = role, content = listOf(ContentPart.Text(text)))

internal fun userMessage(
    text: String,
    attachments: List<AgentEvent.RunStarted.Attachment> = emptyList(),
): ChatMessage {
    val imageByLink = attachments.associateBy { it.link }
    if (imageByLink.isEmpty()) {
        return textMessage(ChatRole.USER, text)
    }
    val parts = buildList {
        var consumed = 0
        for (match in MARKDOWN_IMAGE.findAll(text)) {
            val image = imageByLink[match.groupValues[1]] ?: continue
            add(ContentPart.Text(text.substring(consumed, match.range.last + 1)))
            add(ContentPart.Image(mimeType = image.mimeType, base64Data = image.base64Data))
            consumed = match.range.last + 1
        }
        if (consumed < text.length) {
            add(ContentPart.Text(text.substring(consumed)))
        }
    }
    return ChatMessage(role = ChatRole.USER, content = parts)
}

internal fun toolResultMessage(
    callId: String,
    text: String,
    media: AgentEvent.ToolCallFinished.Media? = null,
): ChatMessage =
    ChatMessage(
        role = ChatRole.TOOL,
        content = when (media) {
            null -> listOf(ContentPart.Text(text))
            else -> listOf(ContentPart.Image(mimeType = media.mimeType, base64Data = media.base64Data))
        },
        toolCallId = callId,
    )

internal val ChatMessage.awaitsModel: Boolean
    get() = role == ChatRole.USER || role == ChatRole.TOOL
