package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories

internal class EventLogWriter(
    private var sessionId: String?,
    private val logFileOf: (sessionId: String) -> Path,
) : AgentEventListener, AutoCloseable {

    private var writer: BufferedWriter? = null

    @Volatile
    var failure: IOException? = null
        private set

    @Synchronized
    override fun onEvent(event: AgentEvent) {
        if (failure != null) {
            return
        }
        try {
            val target = writer ?: openWriter(event).also { writer = it }
            target.write(encodeLogLine(event))
            target.newLine()
            target.flush()
        } catch (appendFailure: IOException) {
            failure = appendFailure
        }
    }

    @Synchronized
    override fun close() {
        try {
            writer?.close()
        } finally {
            writer = null
        }
        failure?.let { throw it }
    }

    private fun openWriter(event: AgentEvent): BufferedWriter {
        val id = sessionId
            ?: (event as? AgentEvent.SessionStarted)?.sessionId?.also { sessionId = it }
            ?: error("a fresh session's first event must be SessionStarted, got: $event")
        val file = logFileOf(id)
        file.parent.createDirectories()
        file.dropTornTail()
        return Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
