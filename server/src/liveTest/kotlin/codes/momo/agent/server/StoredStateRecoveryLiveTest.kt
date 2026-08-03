package codes.momo.agent.server

import codes.momo.agent.harness.harnessPath
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What a fresh server process makes of state an earlier one left behind.
 * Both cases own their processes: the suite's shared one is already running,
 * and a session's stored state is only indexed at startup.
 */
class StoredStateRecoveryLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A session's renamed title survives a restart on a dormant session")
    fun theTitleSurvivesARestart() {
        val dataDir = tempDir.resolve("data")
        val harness = harnessPath(tempDir)

        // First process: rename, then die outright — a crash, not
        // a clean stop, so `use` is not what ends it. The kill still has to be
        // unconditional: an orphaned server outlives the whole Gradle build,
        // holding its port and its data directory.
        val first = LiveServerProcess.start(dataDir)
        val id = try {
            liveHttpClient(first.baseUrl).use { http ->
                runBlocking {
                    val created = http.createSession(harness, localWorkspace(tempDir))
                    http.renameSession(created.id, "Kept title")
                    created.id
                }
            }
        } finally {
            first.crash()
        }

        // Second process over the same data directory.
        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    val info = http.sessionInfo(id)
                    assertEquals("Kept title", info.title)
                    assertEquals(SessionStatus.CLOSED, info.status)
                }
            }
        }
    }

    @Test
    @DisplayName("A stored session whose metadata no longer parses is unreadable but still deletable")
    fun unreadableMetadataSessionCanBeDeleted() {
        val dataDir = tempDir.resolve("data")
        val folder = dataDir.resolve("sessions/broken-session").createDirectories()
        folder.resolve("session.json").writeText("""{"harnessPath":"/h"}""")
        folder.resolve("events.jsonl").writeText(
            """{"type":"session_started","sequenceId":0,"timestampMillis":0,""" +
                """"sessionId":"broken-session","title":"b"}""" + "\n",
        )

        // Planted before the process starts, because that is when a session's
        // stored state becomes known to it.
        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    assertEquals(
                        HttpStatusCode.InternalServerError,
                        http.get("/v1/sessions/broken-session").status,
                    )
                    assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/broken-session").status)
                    assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/broken-session").status)
                    assertFalse(folder.isDirectory())
                }
            }
        }
    }
}
