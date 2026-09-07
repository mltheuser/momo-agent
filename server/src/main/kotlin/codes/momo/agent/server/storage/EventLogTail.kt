package codes.momo.agent.server.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal const val BEFORE_FIRST_EVENT = -1L

internal class EventLogSignal {

    private val state = MutableStateFlow(LogState(lastSequenceId = BEFORE_FIRST_EVENT, cuts = 0, deleted = false))

    val states: StateFlow<LogState>
        get() = state

    fun appended(sequenceId: Long) {
        state.update { it.copy(lastSequenceId = sequenceId) }
    }

    fun cut(rewoundSequenceId: Long) {
        state.update { it.copy(lastSequenceId = rewoundSequenceId, cuts = it.cuts + 1) }
    }

    fun deleted() {
        state.update { it.copy(deleted = true) }
    }
}

internal data class LogState(val lastSequenceId: Long, val cuts: Int, val deleted: Boolean)

internal fun Path.tailLogLines(signal: EventLogSignal, afterSequenceId: Long): Flow<LogLine> = flow {
    var reader = LineReader(this@tailLogLines)
    try {
        var cutsSeen = signal.states.value.cuts
        var lastEmitted = afterSequenceId
        signal.states.takeWhile { !it.deleted }.collect { state ->
            if (state.cuts != cutsSeen) {
                cutsSeen = state.cuts
                reader.close()
                reader = LineReader(this@tailLogLines)
            }
            lastEmitted = emitNewLines(reader, lastEmitted)
        }
    } finally {
        reader.close()
    }
}.flowOn(Dispatchers.IO)

private suspend fun FlowCollector<LogLine>.emitNewLines(reader: LineReader, lastEmitted: Long): Long {
    var newest = lastEmitted
    var line = reader.nextLine()
    while (line != null) {
        if (line.isNotBlank()) {
            val parsed = parseLogLine(line)
            if (parsed.sequenceId > newest) {
                emit(parsed)
                newest = parsed.sequenceId
            }
        }
        line = reader.nextLine()
    }
    return newest
}

private class LineReader(private val file: Path) : AutoCloseable {

    private var input: InputStream? = null

    private val partial = ByteArrayOutputStream()

    fun nextLine(): String? {
        val stream = input ?: openIfPresent() ?: return null
        var byte = stream.read()
        while (byte >= 0 && byte != '\n'.code) {
            partial.write(byte)
            byte = stream.read()
        }
        return if (byte < 0) {
            null
        } else {
            val line = partial.toString(Charsets.UTF_8)
            partial.reset()
            line
        }
    }

    override fun close() {
        input?.close()
    }

    private fun openIfPresent(): InputStream? = try {
        BufferedInputStream(Files.newInputStream(file)).also { input = it }
    } catch (_: NoSuchFileException) {
        null
    }
}
