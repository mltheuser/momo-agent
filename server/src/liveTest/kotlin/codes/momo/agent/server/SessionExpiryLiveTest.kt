package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.eventsResponse
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.storage.SESSION_RETENTION
import codes.momo.agent.server.storage.encodeLogLine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class SessionExpiryLiveTest {

    @TempDir
    lateinit var dataDir: Path

    @Test
    @DisplayName("A server start deletes each session tree none of whose logs was written within the retention period")
    fun aStartDeletesTheTreesLeftUnwrittenForTheRetentionPeriod() {
        val kept = mapOf(
            store("old-root", OLD) to "a recently written child keeps its old root",
            store("recent-child", RECENT, parent = "old-root") to "a recently written child is kept",
            store("recent-root", RECENT) to "a recently written root is kept",
            store("old-child", OLD, parent = "recent-root") to "a recently written root keeps its old child",
        )
        val expired = mapOf(
            store("expired-root", OLD) to "a root expires once its whole tree is old",
            store("expired-child", OLD, parent = "expired-root") to "an expired root takes its child with it",
            storeLog("corrupt", OLD, NOT_UTF_8) to "a corrupt log is a tree of its own and expires by its own age",
        )

        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    kept.forEach { (id, fact) -> assertEquals(HttpStatusCode.OK, http.eventsResponse(id).status, fact) }
                    expired.forEach { (id, fact) ->
                        assertEquals(HttpStatusCode.NotFound, http.eventsResponse(id).status, fact)
                    }
                }
            }
        }
        assertEquals(
            kept.keys,
            dataDir.resolve("sessions").listDirectoryEntries().map { it.name }.toSet(),
            "an expired tree leaves the store",
        )
    }

    private fun store(id: String, age: Duration, parent: String? = null): String {
        val started = AgentEvent.SessionStarted(
            sequenceId = 0,
            timestampMillis = 0,
            sessionId = id,
            title = id,
            harnessPath = null,
            workspace = "/work",
            parent = parent,
            settings = null,
        )
        return storeLog(id, age, "${encodeLogLine(started)}\n".toByteArray())
    }

    private fun storeLog(id: String, age: Duration, content: ByteArray): String {
        val log = dataDir.resolve("sessions/$id").createDirectories().resolve("events.jsonl")
        log.writeBytes(content)
        log.setLastModifiedTime(FileTime.fromMillis(System.currentTimeMillis() - age.inWholeMilliseconds))
        return id
    }
}

private val OLD: Duration = SESSION_RETENTION + 1.days

private val RECENT: Duration = SESSION_RETENTION - 1.days

private val NOT_UTF_8: ByteArray = byteArrayOf(0xFF.toByte())
