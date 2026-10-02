package codes.momo.agent.content

import java.util.Base64

internal sealed interface ModelContent {

    val charCount: Int

    val asText: String

    fun fittedTo(maxChars: Int): ModelContent

    fun fitsIn(maxChars: Int): Boolean = charCount <= maxChars

    data class Text(val text: String) : ModelContent {

        override val charCount: Int get() = text.length

        override val asText: String get() = text

        override fun fittedTo(maxChars: Int): ModelContent {
            if (fitsIn(maxChars)) return this
            val cut = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
            return Text(text.take(cut) + truncationMarker(maxChars))
        }
    }

    class Media private constructor(val mimeType: String, val base64Data: String) : ModelContent {

        override val charCount: Int get() = base64Data.length

        override val asText: String get() = "[$mimeType]"

        override fun fittedTo(maxChars: Int): ModelContent =
            if (fitsIn(maxChars)) {
                this
            } else {
                Text("[$mimeType omitted: larger than the limit of ${maxBytesWithin(maxChars)} bytes]")
            }

        companion object {

            fun of(mimeType: String, bytes: ByteArray): Media =
                Media(mimeType, Base64.getEncoder().encodeToString(bytes))

            fun charCountOf(byteCount: Int): Int =
                (byteCount + BYTES_PER_BASE64_GROUP - 1) / BYTES_PER_BASE64_GROUP * CHARS_PER_BASE64_GROUP

            fun maxBytesWithin(maxChars: Int): Int = maxChars / CHARS_PER_BASE64_GROUP * BYTES_PER_BASE64_GROUP
        }
    }
}

public fun truncationMarker(limit: Int): String = "\n[output truncated: exceeded $limit characters]"

private const val BYTES_PER_BASE64_GROUP: Int = 3

private const val CHARS_PER_BASE64_GROUP: Int = 4
