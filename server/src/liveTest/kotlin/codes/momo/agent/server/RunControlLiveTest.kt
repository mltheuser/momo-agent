package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.session.SessionStatus
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)

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

private const val SLOW_PROMPT: String =
    "Using the bash tool, run the command 'sleep 5 && echo done' and then report what it printed."

private const val RESUME_PROMPT: String =
    "That command is no longer needed. Without using any tools, tell me what two plus two is."
