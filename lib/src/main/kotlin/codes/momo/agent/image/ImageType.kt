package codes.momo.agent.image

import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType
import org.apache.tika.mime.MimeTypes
import org.apache.tika.parser.ParseContext

internal val TIKA_IMAGE_TYPES: Set<String> = setOf(
    "image/avif",
    "image/bmp",
    "image/gif",
    "image/heic",
    "image/heic-sequence",
    "image/heif",
    "image/heif-sequence",
    "image/jp2",
    "image/jpeg",
    "image/jxl",
    "image/png",
    "image/svg+xml",
    "image/tiff",
    "image/vnd.microsoft.icon",
    "image/webp",
)

internal fun detectMediaType(bytes: ByteArray, fileName: String): String {
    val detectedFromBytes = detect(bytes, fileName = null)
    val refinedForText = if (detectedFromBytes.isText) detect(bytes, fileName) else detectedFromBytes
    return refinedForText.baseType.toString()
}

private fun detect(bytes: ByteArray, fileName: String?): MediaType {
    val metadata = Metadata()
    fileName?.let { metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, it) }
    return TikaInputStream.get(bytes).use { stream -> tikaMimeTypes.detect(stream, metadata, ParseContext()) }
}

private val MediaType.isText: Boolean
    get() = tikaMimeTypes.mediaTypeRegistry.isInstanceOf(this, MediaType.TEXT_PLAIN)

private val tikaMimeTypes: MimeTypes by lazy { MimeTypes.getDefaultMimeTypes() }
