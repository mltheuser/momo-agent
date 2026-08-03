package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Session persistence across a restart, proven by two real server processes
 * over one data directory. The suite's shared process cannot show this, so
 * this case owns its own pair.
 */
class ServerRestartLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A session survives a server restart and answers the next prompt in the same conversation")
    fun aSessionSurvivesARestartAndKeepsItsConversation() {
        val dataDir = tempDir.resolve("data")
        val harness = liveHarness(tempDir)
        val workspace = localWorkspace(tempDir)
        val tokenFile = Path.of(workspace.workspace).resolve(TOKEN_FILE)
        tokenFile.writeText("$TOKEN\n")

        val before = LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking { http.readTheToken(harness, workspace) }
            }
        }

        // With the file gone, the stored transcript is the token's only
        // surviving copy: recalling it is what survived the restart.
        tokenFile.deleteExisting()

        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking { http.recallTheToken(before, workspace) }
            }
        }
    }
}

/** What the second process has to find: the session, where its log ended, and what it had used. */
private data class FirstProcessOutcome(val id: String, val lastSequenceId: Long, val turnsUsed: Int)

/** First process: one completed run whose answer came out of the workspace. */
private suspend fun HttpClient.readTheToken(
    harness: String,
    workspace: EnvironmentSpec.Local,
): FirstProcessOutcome {
    val id = createSession(harness, workspace).id
    prompt(id, "Read the file $TOKEN_FILE in the workspace with the bash tool and tell me the token it contains.")

    val events = streamEvents(id)
    val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
    assertEquals(RunResult.Status.COMPLETED, finished.status)
    assertContains(assertNotNull(finished.finalMessage), TOKEN, ignoreCase = true)
    return FirstProcessOutcome(id, events.last().id, finished.turnsUsed)
}

/** Second process over the same data directory: the session is listed, dormant, and resumable. */
private suspend fun HttpClient.recallTheToken(before: FirstProcessOutcome, workspace: EnvironmentSpec) {
    assertTrue(
        sessions(workspace).any { it.id == before.id },
        "the restarted server must still list the stored session",
    )
    val reloaded = sessionInfo(before.id)
    // Dormant, not gone: no runtime is attached until the next prompt.
    assertEquals(SessionStatus.CLOSED, reloaded.status)
    assertEquals(before.turnsUsed, reloaded.lastRun?.turnsUsed)

    prompt(before.id, "Remind me of the exact token you read — you already have it in this conversation.")
    val resumed = streamEvents(before.id, afterSequenceId = before.lastSequenceId)

    // One log across two processes: the reloaded session continues the
    // sequence where the dead one left off, with no gap over the join.
    assertEquals(
        List(resumed.size) { before.lastSequenceId + 1 + it },
        resumed.map { it.id },
        "the restarted process must continue the stored log's gapless sequence ids",
    )
    val answer = assertIs<AgentEvent.RunFinished>(resumed.last().event)
    assertEquals(RunResult.Status.COMPLETED, answer.status)
    assertContains(
        assertNotNull(answer.finalMessage),
        TOKEN,
        ignoreCase = true,
        message = "the token is gone from the workspace, so only the reloaded transcript can hold it",
    )
    assertEquals(SessionStatus.IDLE, sessionInfo(before.id).status)
}

private const val TOKEN_FILE: String = "token.txt"

/** The planted needle that must cross the restart inside the stored transcript. */
private const val TOKEN: String = "plugh-2860"
