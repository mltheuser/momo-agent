package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PersistedEventLogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("An append failure never reaches the run, stops the log, and resurfaces on close")
    fun appendFailureIsRecordedAndRethrownOnClose() {
        val sessionsDir = tempDir.resolve("sessions").also { it.writeText("a file where a directory must be") }
        val log = PersistedEventLog(sessionsDir, id = "s1")
        val event = AgentEvent.SessionStarted(sequenceId = 0, timestampMillis = 1, sessionId = "s1", title = "t")

        log.onEvent(event)
        assertNotNull(log.failure, "the failed append must be recorded")
        log.onEvent(event) // Dropped: the log stopped at its intact prefix.

        assertFailsWith<IOException> { log.close() }
    }
}
