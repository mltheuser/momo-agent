package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPartType

/** The message's concatenated text parts. */
public val ChatMessage.text: String
    get() = content.filter { it.type == ContentPartType.TEXT }.mapNotNull { it.text }.joinToString("")
