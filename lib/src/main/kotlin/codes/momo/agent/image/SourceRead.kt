package codes.momo.agent.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal sealed interface SourceRead {

    class Read(val bytes: ByteArray, val fileName: String) : SourceRead

    data class Unreadable(val problem: String) : SourceRead
}

internal suspend fun readSource(source: String, workspacePath: String, readLimitBytes: Int): SourceRead =
    if (WEB_URL.containsMatchIn(source)) {
        fetch(source, readLimitBytes)
    } else {
        readFile(source, workspacePath, readLimitBytes)
    }

private suspend fun fetch(url: String, readLimitBytes: Int): SourceRead {
    val request = try {
        HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT_WITH_CONTACT).GET().build()
    } catch (_: IllegalArgumentException) {
        return SourceRead.Unreadable("it is not a valid URL")
    }
    return withTimeoutOrNull(FETCH_TIMEOUT) { runInterruptible(Dispatchers.IO) { download(request, readLimitBytes) } }
        ?: SourceRead.Unreadable("the download did not finish within $FETCH_TIMEOUT")
}

private fun download(request: HttpRequest, readLimitBytes: Int): SourceRead = try {
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { body ->
        when (val status = response.statusCode()) {
            !in HTTP_SUCCESS -> SourceRead.Unreadable("the server answered HTTP $status")
            else -> SourceRead.Read(body.readNBytes(readLimitBytes), lastPathSegment(response.uri()))
        }
    }
} catch (_: HttpTimeoutException) {
    SourceRead.Unreadable("could not connect to ${request.uri().host} within $FETCH_TIMEOUT")
} catch (failure: ConnectException) {
    val host = request.uri().host
    SourceRead.Unreadable(if (failure.isUnresolved) "unknown host $host" else "could not connect to $host")
} catch (failure: IOException) {
    SourceRead.Unreadable("the download failed: $failure")
}

private val Throwable.isUnresolved: Boolean
    get() = generateSequence(this) { it.cause }.any { it is UnresolvedAddressException }

private fun lastPathSegment(uri: URI): String = uri.path.orEmpty().substringAfterLast('/')

private suspend fun readFile(source: String, workspacePath: String, readLimitBytes: Int): SourceRead {
    val path = try {
        Path.of(workspacePath).resolve(expandHome(source))
    } catch (_: InvalidPathException) {
        return SourceRead.Unreadable("it is not a valid path")
    }
    return when {
        path.isDirectory() -> SourceRead.Unreadable("it is a directory")
        !Files.exists(path) -> SourceRead.Unreadable("no such file: $path")
        path.mayBlockWhenOpened() -> SourceRead.Unreadable("it is not a regular file")
        else -> try {
            runInterruptible(Dispatchers.IO) {
                SourceRead.Read(Files.newInputStream(path).use { it.readNBytes(readLimitBytes) }, path.name)
            }
        } catch (_: NoSuchFileException) {
            SourceRead.Unreadable("no such file: $path")
        } catch (_: AccessDeniedException) {
            SourceRead.Unreadable("permission denied: $path")
        } catch (failure: IOException) {
            SourceRead.Unreadable("reading $path failed: $failure")
        }
    }
}

private fun Path.mayBlockWhenOpened(): Boolean = !isRegularFile()

private fun expandHome(source: String): String = when {
    source == "~" || source.startsWith("~/") -> System.getProperty("user.home") + source.drop(1)
    else -> source
}

private val WEB_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

private val FETCH_TIMEOUT = 30.seconds

private const val HTTP_FIRST_SUCCESS: Int = 200

private const val HTTP_LAST_SUCCESS: Int = 299

private val HTTP_SUCCESS: IntRange = HTTP_FIRST_SUCCESS..HTTP_LAST_SUCCESS

private const val USER_AGENT_WITH_CONTACT: String = "momo-agent (+https://github.com/mltheuser/momo-agent)"

private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(FETCH_TIMEOUT.toJavaDuration())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}
