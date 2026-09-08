package codes.momo.agent.server.storage

import codes.momo.agent.AgentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.readLines

internal class LogLine(val sequenceId: Long, val type: String, val json: String)

@Serializable
private class LogLineHeader(val sequenceId: Long, val type: String)

private val partialJson = Json { ignoreUnknownKeys = true }

internal inline fun <reified T> decodeLogLineAs(line: String): T = partialJson.decodeFromString(line)

internal fun parseLogLine(line: String): LogLine {
    val header = decodeLogLineAs<LogLineHeader>(line)
    return LogLine(header.sequenceId, header.type, line)
}

internal fun encodeLogLine(event: AgentEvent): String = Json.encodeToString(event)

internal fun <T : Any> Path.readLogLines(sessionId: String, parse: (String) -> T): List<T> {
    val lines = readLines().filter { it.isNotBlank() }
    return lines.mapIndexedNotNull { index, line ->
        try {
            parse(line)
        } catch (failure: SerializationException) {
            if (index == lines.lastIndex) null else throw CorruptSessionException(sessionId, failure.message, failure)
        }
    }
}

internal fun Path.dropTornTail() {
    val channel = try {
        FileChannel.open(this, StandardOpenOption.READ, StandardOpenOption.WRITE)
    } catch (_: NoSuchFileException) {
        return
    }
    channel.use {
        val size = it.size()
        val terminator = ByteBuffer.allocate(1)
        var end = size
        while (end > 0) {
            it.read(terminator.clear(), end - 1)
            if (terminator.get(0) == '\n'.code.toByte()) {
                break
            }
            end--
        }
        if (end < size) {
            it.truncate(end)
        }
    }
}

internal fun <T> Path.readingBackwards(read: (Sequence<String>) -> T): T =
    FileChannel.open(this, StandardOpenOption.READ).use { channel ->
        val chunk = ByteBuffer.allocate(BACKWARD_CHUNK_BYTES)
        var end = channel.size()
        val pending = ByteArrayOutputStream()
        val lines = sequence {
            while (end > 0) {
                val start = maxOf(0L, end - chunk.capacity())
                chunk.clear().limit((end - start).toInt())
                while (chunk.hasRemaining()) {
                    channel.read(chunk, start + chunk.position())
                }
                end = start
                for (index in chunk.limit() - 1 downTo 0) {
                    if (chunk.get(index) == '\n'.code.toByte()) {
                        yield(pending.takeLine())
                    } else {
                        pending.write(chunk.get(index).toInt())
                    }
                }
            }
            yield(pending.takeLine())
        }
        read(lines)
    }

private fun ByteArrayOutputStream.takeLine(): String {
    val line = toByteArray().apply { reverse() }.toString(Charsets.UTF_8)
    reset()
    return line
}

private const val BACKWARD_CHUNK_BYTES: Int = 64 * 1024
