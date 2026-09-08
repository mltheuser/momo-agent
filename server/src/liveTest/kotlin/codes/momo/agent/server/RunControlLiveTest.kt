package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.assertRejected
import codes.momo.agent.server.rig.awaitLogged
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.promptResponse
import codes.momo.agent.server.rig.retryResponse
import codes.momo.agent.server.rig.rewindResponse
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.stopResponse
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.SessionStatus
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RunControlLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Mid-run, prompt/rewind/retry are 409s that change nothing; a stop ends the run stopped and idle")
    fun inFlightGuardsThenStop() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "stopped")).id
        assertEquals(SessionStatus.RUNNING, http.prompt(id, SLOW_PROMPT).status)
        val beforeGuards = http.awaitLogged<AgentEvent.ToolCallStarted>(id)
        val runStart = beforeGuards.first { it is AgentEvent.RunStarted }.sequenceId

        http.promptResponse(id, "impatient follow-up")
            .assertRejected("conflict", "a second prompt during a run", status = HttpStatusCode.Conflict)
        http.rewindResponse(id, runStart)
            .assertRejected("conflict", "a rewind during a run", status = HttpStatusCode.Conflict)
        http.retryResponse(id)
            .assertRejected("conflict", "a retry during a run", status = HttpStatusCode.Conflict)
        assertEquals(SessionStatus.RUNNING, http.sessionInfo(id).status, "the guards left the run in flight")

        val stopped = http.stopResponse(id)
        assertEquals(HttpStatusCode.OK, stopped.status)

        val events = http.awaitRunEnd(id)
        assertEquals(RunResult.Status.STOPPED, assertIs<AgentEvent.RunFinished>(events.last()).status)
        val cut = assertIs<AgentEvent.ToolCallFinished>(events[events.lastIndex - 1], "the stop answers the call")
        assertEquals(AgentEvent.ToolCallFinished.Outcome.ERROR, cut.outcome)
        assertContains(cut.resultText, "a user stopped the run")
        assertEquals(1, events.count { it is AgentEvent.RunStarted }, "the 409s started no run")
        assertEquals(beforeGuards, events.take(beforeGuards.size), "the guards left the log as it was: no cut")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)

        assertEquals(SessionStatus.RUNNING, http.prompt(id, RECALL_PROMPT).status)
        val answer = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(id).last())
        assertEquals(RunResult.Status.COMPLETED, answer.status, "error: ${answer.error}")
        assertContains(assertNotNull(answer.finalMessage), SLOW_COMMAND, message = "the stopped turn is remembered")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }

    @Test
    @DisplayName("A kill mid-run is repaired on the first read: the cut call and the run end, the session carries on")
    fun aKillMidRunIsRepairedOnRestart() {
        val dataDir = tempDir.resolve("data")
        val harness = liveHarness(tempDir)
        val workspace = localWorkspace(tempDir, "killed")

        val killed = LiveServerProcess.start(dataDir)
        val (id, lastStored) = try {
            liveHttpClient(killed.baseUrl).use { http ->
                runBlocking {
                    val id = http.createSession(harness, workspace).id
                    http.prompt(id, SLOW_PROMPT)
                    val running = http.awaitLogged<AgentEvent.ToolCallStarted>(id)
                    id to running.last().sequenceId
                }
            }
        } finally {
            killed.crash()
        }

        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status, "nothing runs after a restart")
                    val stored = http.events(id)
                    val (cutCall, repaired) = stored.takeLast(2)
                    val cut = assertIs<AgentEvent.ToolCallFinished>(cutCall, "the torn call is answered on disk")
                    assertEquals(AgentEvent.ToolCallFinished.Outcome.ERROR, cut.outcome)
                    assertContains(cut.resultText, "the server went down")
                    val finished = assertIs<AgentEvent.RunFinished>(repaired)
                    assertEquals(RunResult.Status.INTERRUPTED, finished.status, "the torn run is ended on first read")
                    assertNull(finished.finalMessage)
                    assertEquals(1, finished.turnsUsed, "the stats come from the run's own events")
                    assertEquals(lastStored + 2, stored.last().sequenceId, "the repairs continue the log without a gap")
                    assertEquals(List(stored.size) { it.toLong() }, stored.map { it.sequenceId }, "the log has gaps")

                    assertEquals(SessionStatus.RUNNING, http.prompt(id, RECALL_PROMPT).status)
                    val answer = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(id).last())
                    assertEquals(RunResult.Status.COMPLETED, answer.status, "error: ${answer.error}")
                    assertContains(
                        assertNotNull(answer.finalMessage),
                        SLOW_COMMAND,
                        message = "the interrupted turn is remembered",
                    )
                    assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
                }
            }
        }
    }
}

private const val SLOW_COMMAND: String = "sleep 5 && echo done"

private const val SLOW_PROMPT: String =
    "Using the bash tool, run the command '$SLOW_COMMAND' and then report what it printed."

private const val RECALL_PROMPT: String =
    "That command is no longer needed and must not run again. Without using any tools, " +
        "tell me the exact shell command I asked you to run."
