package codes.momo.agent.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists

internal sealed interface PageFile {

    data class Saved(val path: String) : PageFile

    data class Failed(val problem: String) : PageFile
}

/**
 * Saves a page's [content] under [PAGE_DIR], named after its [url] and a hash of the content: a name
 * always holds the same content, so an existing file is kept as it is.
 */
internal suspend fun savePage(url: String, content: ByteArray): PageFile = withContext(Dispatchers.IO) {
    val target = Path.of(PAGE_DIR, pageFileName(url, content))
    try {
        if (!target.exists()) writeAtomically(target, content)
        PageFile.Saved(target.toString())
    } catch (failure: IOException) {
        PageFile.Failed(failure.toString())
    }
}

private fun writeAtomically(target: Path, content: ByteArray) {
    Files.createDirectories(target.parent)
    val staging = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.write(staging, content)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(staging)
    }
}

private fun pageFileName(url: String, content: ByteArray): String {
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.lowercase()?.removePrefix("www.")?.let { slug(it, NOT_IN_HOST, MAX_HOST_CHARS) }
    val path = uri?.path.orEmpty().split('/').filter { it.isNotEmpty() }.takeLast(SLUG_SEGMENTS).joinToString("-")
    val slug = slug(path, NOT_IN_SLUG, MAX_SLUG_CHARS)
    return "${host.orEmpty().ifEmpty { "page" }}-${slug.ifEmpty { "index" }}-${hash(content)}.md"
}

private fun slug(text: String, disallowed: Regex, maxChars: Int): String =
    text.lowercase().replace(disallowed, "-").trim('-', '.').takeLast(maxChars).trim('-', '.')

private fun hash(content: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(content).take(HASH_BYTES).joinToString("") { "%02x".format(it) }

private val NOT_IN_HOST = Regex("[^a-z0-9.]+")

private val NOT_IN_SLUG = Regex("[^a-z0-9]+")

internal const val PAGE_DIR: String = "/tmp/momo-web"

private const val MAX_HOST_CHARS: Int = 60

private const val MAX_SLUG_CHARS: Int = 40

private const val SLUG_SEGMENTS: Int = 2

private const val HASH_BYTES: Int = 4
