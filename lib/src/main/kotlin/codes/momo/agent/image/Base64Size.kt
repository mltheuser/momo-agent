package codes.momo.agent.image

internal fun base64EncodedLength(byteCount: Int): Int =
    (byteCount + BYTES_PER_BASE64_GROUP - 1) / BYTES_PER_BASE64_GROUP * CHARS_PER_BASE64_GROUP

internal fun maxBytesWhoseBase64FitsIn(charCount: Int): Int =
    charCount / CHARS_PER_BASE64_GROUP * BYTES_PER_BASE64_GROUP

private const val BYTES_PER_BASE64_GROUP: Int = 3

private const val CHARS_PER_BASE64_GROUP: Int = 4
