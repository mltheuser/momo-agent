package codes.momo.agent

import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.tool.MAX_IMAGE_BYTES
import codes.momo.agent.tool.ToolResult
import codes.momo.agent.tool.imageMimeType
import codes.momo.agent.tool.loadImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

internal val MARKDOWN_IMAGE: Regex = Regex("""!\[[^\]]*]\(([^()\s]+)\)""")

internal suspend fun resolvePromptAttachments(
    prompt: String,
    environment: ExecutionEnvironment,
): List<AgentEvent.RunStarted.Attachment> =
    MARKDOWN_IMAGE.findAll(prompt).map { it.groupValues[1] }.distinct().toList()
        .mapNotNull { link ->
            resolved(link, environment)?.let { AgentEvent.RunStarted.Attachment(link, it.mimeType, it.base64Data) }
        }

private suspend fun resolved(link: String, environment: ExecutionEnvironment): ToolResult.Image? = try {
    when {
        link.startsWith("http://") || link.startsWith("https://") -> fetched(link)
        else -> loadImage(link, environment) as? ToolResult.Image
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    null
}

private suspend fun fetched(url: String): ToolResult.Image? =
    runInterruptible(Dispatchers.IO) { download(url) }

private fun download(url: String): ToolResult.Image? {
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT).GET().build()
    val response = fetchClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    val bytes = response.body().use { body ->
        when {
            response.statusCode() != HTTP_OK -> null
            else -> body.readNBytes(MAX_IMAGE_BYTES.toInt() + 1).takeIf { it.size <= MAX_IMAGE_BYTES }
        }
    } ?: return null
    val declared = response.headers().firstValue("Content-Type").orElse("")
        .substringBefore(';').trim().lowercase()
    val mimeType = declared.takeIf { it.startsWith("image/") } ?: imageMimeType(URI.create(url).path.orEmpty())
    return ToolResult.Image(mimeType, Base64.getEncoder().encodeToString(bytes))
}

private val fetchClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(FETCH_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(FETCH_TIMEOUT_SECONDS)

private const val FETCH_TIMEOUT_SECONDS: Long = 30

private const val HTTP_OK: Int = 200
