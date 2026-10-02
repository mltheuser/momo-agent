package codes.momo.agent.image

import codes.momo.agent.content.ModelContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface LoadedImage {

    class Loaded(val image: ModelContent.Media) : LoadedImage

    data class Failed(val problem: String) : LoadedImage
}

internal suspend fun loadImage(source: String, workspacePath: String, maxChars: Int): LoadedImage {
    val readEnoughToExceedMaxChars = ModelContent.Media.maxBytesWithin(maxChars) + 1
    return when (val read = readSource(source, workspacePath, readEnoughToExceedMaxChars)) {
        is SourceRead.Unreadable -> LoadedImage.Failed(read.problem)
        is SourceRead.Read -> identified(read)
    }
}

private suspend fun identified(read: SourceRead.Read): LoadedImage {
    if (read.bytes.isEmpty()) return LoadedImage.Failed("it is empty")
    val mediaType = withContext(Dispatchers.IO) { detectMediaType(read.bytes, read.fileName) }
    return when (mediaType) {
        in TIKA_IMAGE_TYPES -> LoadedImage.Loaded(ModelContent.Media.of(mediaType, read.bytes))
        else -> LoadedImage.Failed("it is not an image: its content is $mediaType")
    }
}
