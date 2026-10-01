package codes.momo.agent.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface LoadedImage {

    class Loaded(val mimeType: String, val bytes: ByteArray) : LoadedImage

    data class Failed(val problem: String) : LoadedImage
}

internal suspend fun loadImage(source: String, workspacePath: String, readLimitBytes: Int): LoadedImage =
    when (val read = readSource(source, workspacePath, readLimitBytes)) {
        is SourceRead.Unreadable -> LoadedImage.Failed(read.problem)
        is SourceRead.Read -> identified(read)
    }

private suspend fun identified(read: SourceRead.Read): LoadedImage {
    if (read.bytes.isEmpty()) return LoadedImage.Failed("it is empty")
    val mediaType = withContext(Dispatchers.IO) { detectMediaType(read.bytes, read.fileName) }
    return when (mediaType) {
        in TIKA_IMAGE_TYPES -> LoadedImage.Loaded(mediaType, read.bytes)
        else -> LoadedImage.Failed("it is not an image: its content is $mediaType")
    }
}

internal const val MAX_IMAGE_BYTES: Int = 5 * 1024 * 1024
