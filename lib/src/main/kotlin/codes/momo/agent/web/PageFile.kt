package codes.momo.agent.web

import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.problem
import codes.momo.agent.environment.succeeded
import codes.momo.agent.tool.TOOL_TIMEOUT
import java.net.URI
import java.security.MessageDigest

internal sealed interface PageFile {

    data class Saved(val path: String) : PageFile

    data class Failed(val problem: String) : PageFile
}

/**
 * Saves a page's [content] under [PAGE_DIR], named after its [url] and a hash of the content: a name
 * always holds the same content, so an existing file is kept as it is.
 */
internal suspend fun savePage(url: String, content: ByteArray, environment: ExecutionEnvironment): PageFile {
    val name = pageFileName(url, content)
    val saved = environment.exec(
        listOf("bash", "-c", SAVE_SCRIPT, "page_contents", PAGE_DIR, name),
        timeout = TOOL_TIMEOUT,
        stdin = content,
    )
    return if (saved.succeeded) PageFile.Saved("$PAGE_DIR/$name") else PageFile.Failed(saved.problem())
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

// Writes stdin to "$1/$2" unless that exists. The write goes through a temp file named after the shell's
// pid, unique among running writers, so no reader ever sees a partial file.
private val SAVE_SCRIPT: String = """
    set -e
    mkdir -p "$1"
    [ -e "$1/$2" ] && exit 0
    trap 'rm -f "$1/.$2.$$"' EXIT
    cat > "$1/.$2.$$"
    mv -f "$1/.$2.$$" "$1/$2"
""".trimIndent()

private const val MAX_HOST_CHARS: Int = 60

private const val MAX_SLUG_CHARS: Int = 40

private const val SLUG_SEGMENTS: Int = 2

private const val HASH_BYTES: Int = 4
