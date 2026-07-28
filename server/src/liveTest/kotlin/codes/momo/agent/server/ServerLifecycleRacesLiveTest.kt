package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The lifecycle races against a real model. They need no pausable fake:
 * `POST /prompt` returns as soon as the run is claimed, and the window the
 * stop and the close need is one the run itself opens — a sleeping tool
 * call, waited for rather than raced.
 */
class ServerLifecycleRacesLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A prompt while a run is active is a 409 conflict; the run it raced then finishes cleanly")
    fun promptWhileRunningConflicts() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "conflict")).id
        assertEquals(SessionStatus.RUNNING, http.prompt(id, READY_PROMPT).status)

        val conflict = http.promptResponse(id, "impatient follow-up")

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("conflict", conflict.body<ApiError>().code)

        val events = http.streamEvents(id)
        assertEquals(1, events.count { it.event is AgentEvent.RunStarted }, "the 409 started no run")
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(events.last().event).status)
    }

    @Test
    @DisplayName("Stopping a run in flight ends it as stopped and leaves the session idle and promptable at once")
    fun stopEndsAnInFlightRunAndKeepsTheSessionPromptable() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "stopped")).id
        http.prompt(id, SLOW_PROMPT)
        // Waited for, not raced: with the tool call started the stop lands
        // inside the sleep, so it is the process tree that has to come down.
        http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })

        assertEquals(HttpStatusCode.OK, http.post("/v1/sessions/$id/stop").status)

        val events = http.streamEvents(id)
        assertEquals(RunResult.Status.STOPPED, assertIs<AgentEvent.RunFinished>(events.last().event).status)
        // Idle, not closed: the runtime stayed attached, so nothing is rebuilt.
        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)

        // Promptable at once, over the same collaborators — and awaited, so
        // this case leaves no run competing with the next for the model.
        assertEquals(SessionStatus.RUNNING, http.prompt(id, RESUME_PROMPT).status)
        http.awaitRunEnd(id)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }

    @Test
    @DisplayName("Closing a session with a run in flight aborts the run, and the next prompt resumes it")
    fun closeAbortsAnInFlightRunAndTheSessionResumes() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "closed")).id
        http.prompt(id, SLOW_PROMPT)
        // As in the stop case: the close lands inside the sleep, not wherever
        // the first LLM call happens to be.
        http.streamEvents(id, until = { it is AgentEvent.ToolCallStarted })

        assertEquals(HttpStatusCode.OK, http.post("/v1/sessions/$id/close").status)

        assertEquals(SessionStatus.CLOSED, http.sessionInfo(id).status)

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
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(events.last().event).status)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
    }
}

/** A prompt that is over in one turn — enough window for a command issued immediately after the 202. */
private const val READY_PROMPT: String = "Reply with the single word: ready."

/** A prompt whose tool call is the slow part, so a command awaiting that call lands mid-execution. */
private const val SLOW_PROMPT: String =
    "Using the bash tool, run the command 'sleep 5 && echo done' and then report what it printed."

/** Picks up after a cut-short command with work of its own, so the next run is short. */
private const val RESUME_PROMPT: String =
    "That command is no longer needed. Tell me what two plus two is."
