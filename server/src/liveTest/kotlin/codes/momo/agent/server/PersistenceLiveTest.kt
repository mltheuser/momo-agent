package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.renameSession
import codes.momo.agent.server.rig.selectModel
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.sessions
import codes.momo.agent.server.rig.streamEvents
import codes.momo.agent.server.session.ModelSelection
import codes.momo.agent.server.session.SessionStatus
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
        val tokenFile = Path.of(workspace).resolve(TOKEN_FILE)
        tokenFile.writeText("$TOKEN\n")

        val before = LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking { http.readTheTokenAndDecorate(harness, workspace) }
            }
        }

        tokenFile.deleteExisting()

        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking { http.recallTheToken(before, workspace) }
            }
        }
    }
}

private data class FirstProcessOutcome(val id: String, val lastSequenceId: Long, val turnsUsed: Int)

private suspend fun HttpClient.readTheTokenAndDecorate(
    harness: String,
    workspace: String,
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

    val lastStored = streamEvents(id, afterSequenceId = events.last().id) { it is AgentEvent.ModelSelected }.last().id
    return FirstProcessOutcome(id, lastStored, finished.turnsUsed)
}

private suspend fun HttpClient.recallTheToken(before: FirstProcessOutcome, workspace: String) {
    assertTrue(
        sessions(workspace).any { it.id == before.id },
        "the restarted server must still list the stored session",
    )
    val reloaded = sessionInfo(before.id)

    assertEquals(SessionStatus.IDLE, reloaded.status)
    assertEquals(before.turnsUsed, reloaded.lastRun?.turnsUsed)
    assertEquals(KEPT_TITLE, reloaded.title, "the title is derived from the stored log")
    assertEquals(
        ModelSelection(PICKED_MODEL, ReasoningEffort.HIGH),
        reloaded.modelSelection,
        "the selection is derived from the stored log",
    )

    prompt(before.id, "Remind me of the exact token you read — you already have it in this conversation.")
    val resumed = streamEvents(before.id, afterSequenceId = before.lastSequenceId)

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

private const val TOKEN: String = "plugh-2860"

private const val KEPT_TITLE: String = "Kept title"

private const val PICKED_MODEL: String = "picked-model"
