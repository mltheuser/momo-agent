package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Commands against a run in flight, with the window opened by the run itself:
 * a dictated `sleep 5` tool call, waited for rather than raced, so every
 * command below lands mid-execution wherever the model's latency happens to
 * be. Stop and close differ on purpose — a stop records `stopped` and keeps
 * the tree attached; a close aborts and leaves the log to repair on reload.
 */
class RunControlLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Mid-run, prompt/rewind/retry are 409s that change nothing; a stop ends the run stopped and idle")
    fun inFlightGuardsThenStop() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "stopped")).id
        assertEquals(SessionStatus.RUNNING, http.prompt(id, SLOW_PROMPT).status)
        val beforeGuards = http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })
        val runStart = beforeGuards.first { it.event is AgentEvent.RunStarted }.id

        val prompt = http.promptResponse(id, "impatient follow-up")
        assertEquals(HttpStatusCode.Conflict, prompt.status, "a second prompt during a run")
        assertEquals("conflict", prompt.body<ApiError>().code)
        val rewind = http.rewindResponse(id, runStart)
        assertEquals(HttpStatusCode.Conflict, rewind.status, "a rewind during a run")
        assertEquals("conflict", rewind.body<ApiError>().code)
        val retry = http.retryResponse(id)
        assertEquals(HttpStatusCode.Conflict, retry.status, "a retry during a run")
        assertEquals("conflict", retry.body<ApiError>().code)
        assertEquals(SessionStatus.RUNNING, http.sessionInfo(id).status, "the guards left the run in flight")

        val stopped = http.stopResponse(id)
        assertEquals(HttpStatusCode.OK, stopped.status)

        val events = http.streamEvents(id)
        assertEquals(RunResult.Status.STOPPED, assertIs<AgentEvent.RunFinished>(events.last().event).status)
        assertEquals(1, events.count { it.event is AgentEvent.RunStarted }, "the 409s started no run")
        assertEquals(
            beforeGuards,
            events.take(beforeGuards.size),
            "the guards left the log as it was: no rewind announcement, no cut",
        )
        // Idle, not closed: the runtime stayed attached, so nothing is rebuilt.
        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)

        // Promptable at once, over the same collaborators.
        assertEquals(SessionStatus.RUNNING, http.prompt(id, RESUME_PROMPT).status)
        val resumed = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = events.last().id).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, resumed.status, "error: ${resumed.error}")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }

    @Test
    @DisplayName("Closing a session with a run in flight aborts the run, and the next prompt resumes it")
    fun closeAbortsAnInFlightRunAndTheSessionResumes() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "closed")).id
        http.prompt(id, SLOW_PROMPT)
        http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })

        assertEquals(SessionStatus.CLOSED, http.closeSession(id).status)

        // The aborted run's repaired log reloads into a usable session. The
        // prompt retires the abandoned command rather than leaving the model
        // to decide whether to try it again.
        assertEquals(SessionStatus.RUNNING, http.prompt(id, RESUME_PROMPT).status)
        val events = http.streamEvents(id)

        assertEquals(2, events.count { it.event is AgentEvent.RunStarted }, "the aborted run plus the resumed one")
        assertEquals(
            1,
            events.count { it.event is AgentEvent.RunFinished },
            "an abort leaves its run without a RunFinished; only the resumed run has one",
        )
        val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }
}

/** A prompt whose tool call is the slow part, so a command awaiting that call lands mid-execution. */
private const val SLOW_PROMPT: String =
    "Using the bash tool, run the command 'sleep 5 && echo done' and then report what it printed."

/** Picks up after a cut-short command with work of its own, so the next run is short. */
private const val RESUME_PROMPT: String =
    "That command is no longer needed. Without using any tools, tell me what two plus two is."
