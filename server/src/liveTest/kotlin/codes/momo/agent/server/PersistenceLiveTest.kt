package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
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
 * What a fresh server process makes of the state an earlier one left behind,
 * proven by two real processes over one data directory. The suite's shared
 * process cannot show this — a session's stored state is indexed at startup
 * — so each case owns its own pair.
 */
class PersistenceLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "A restart keeps the conversation, the title and the model selection, and the next prompt continues it"
    )
    fun aRestartKeepsConversationTitleAndSelection() {
        val dataDir = tempDir.resolve("data")
        val harness = liveHarness(tempDir)
        val workspace = localWorkspace(tempDir)
        val tokenFile = Path.of(workspace.workspace).resolve(TOKEN_FILE)
        tokenFile.writeText("$TOKEN\n")

        val before = LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking { http.readTheTokenAndDecorate(harness, workspace) }
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

    @Test
    @DisplayName(
        "A crash mid-run leaves a resumable session: closed on restart, its log unfinished, the next prompt completes"
    )
    fun aCrashMidRunLeavesAResumableSession() {
        val dataDir = tempDir.resolve("data")
        val harness = liveHarness(tempDir)
        val workspace = localWorkspace(tempDir)

        // Killed outright — a crash, not a stop, so `use` is not what ends
        // it. The kill still has to be unconditional: an orphaned server
        // outlives the whole Gradle build, holding its port and its data
        // directory.
        val first = LiveServerProcess.start(dataDir)
        val id = try {
            liveHttpClient(first.baseUrl).use { http ->
                runBlocking {
                    val id = http.createSession(harness, workspace).id
                    http.prompt(id, SLOW_PROMPT)
                    // Inside the sleep: the log holds a started tool call and no outcome.
                    http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })
                    id
                }
            }
        } finally {
            first.crash()
        }

        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    val reloaded = http.sessionInfo(id)
                    assertEquals(SessionStatus.CLOSED, reloaded.status, "dormant, not gone")
                    val stored = http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })
                    assertTrue(
                        stored.none { it.event is AgentEvent.RunFinished },
                        "a crash records no outcome: the log ends without a run_finished",
                    )

                    // The repaired log reloads into a usable session. The
                    // prompt retires the abandoned command rather than leaving
                    // the model to decide whether to try it again.
                    http.prompt(id, "That command is no longer needed. Without using any tools, reply: resumed.")
                    val events = http.streamEvents(id)
                    assertEquals(
                        2,
                        events.count { it.event is AgentEvent.RunStarted },
                        "the crashed run plus the new one"
                    )
                    assertEquals(
                        1,
                        events.count { it.event is AgentEvent.RunFinished },
                        "only the new run has an outcome"
                    )
                    val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
                    assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
                    assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
                }
            }
        }
    }
}

/** What the second process has to find: the session, where its log ended, and what it had used. */
private data class FirstProcessOutcome(val id: String, val lastSequenceId: Long, val turnsUsed: Int)

/** First process: one completed run whose answer came out of the workspace, then a rename and a selection. */
private suspend fun HttpClient.readTheTokenAndDecorate(
    harness: String,
    workspace: EnvironmentSpec.Local,
): FirstProcessOutcome {
    val id = createSession(harness, workspace).id
    prompt(id, "Read the file $TOKEN_FILE in the workspace with the bash tool and tell me the token it contains.")

    val events = streamEvents(id)
    val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
    assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
    assertContains(assertNotNull(finished.finalMessage), TOKEN, ignoreCase = true)
    awaitRunEnd(id)
    renameSession(id, KEPT_TITLE)
    selectModel(id, PICKED_MODEL, ReasoningEffort.HIGH)
    // The selection is the log's last event: where the second process has to pick up.
    val lastStored = streamEvents(id, afterSequenceId = events.last().id) { it is AgentEvent.ModelSelected }.last().id
    return FirstProcessOutcome(id, lastStored, finished.turnsUsed)
}

/** Second process over the same data directory: the session is listed, dormant, decorated, and resumable. */
private suspend fun HttpClient.recallTheToken(before: FirstProcessOutcome, workspace: EnvironmentSpec) {
    assertTrue(
        sessions(workspace).any { it.id == before.id },
        "the restarted server must still list the stored session",
    )
    val reloaded = sessionInfo(before.id)
    // Dormant, not gone: no runtime is attached until the next prompt.
    assertEquals(SessionStatus.CLOSED, reloaded.status)
    assertEquals(before.turnsUsed, reloaded.lastRun?.turnsUsed)
    assertEquals(KEPT_TITLE, reloaded.title, "the title is derived from the stored log")
    assertEquals(
        ModelSelection(PICKED_MODEL, ReasoningEffort.HIGH),
        reloaded.modelSelection,
        "the selection is derived from the stored log",
    )

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
    assertEquals(RunResult.Status.COMPLETED, answer.status, "error: ${answer.error}")
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

private const val KEPT_TITLE: String = "Kept title"

/** Any string: the selection is stored as given, never validated against the catalog. */
private const val PICKED_MODEL: String = "picked-model"

/** A prompt whose tool call is the slow part, so the crash lands mid-execution. */
private const val SLOW_PROMPT: String =
    "Using the bash tool, run the command 'sleep 5 && echo done' and then report what it printed."
