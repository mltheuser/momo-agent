package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.assertRejected
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.rewindResponse
import codes.momo.agent.server.rig.rewindSession
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.SessionStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewindLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "A rewind from a user message deletes that run whole, reads idle, and no longer knows the deleted turns"
    )
    fun rewindForgetsTheDeletedTurns() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Remember this passphrase: $KEPT_TOKEN — I will ask you to repeat it later.")
        val firstRun = http.awaitRunEnd(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(firstRun.last()).status)

        http.prompt(id, "A second passphrase to remember: $DELETED_TOKEN — I may ask for that one too.")
        val bothRuns = http.awaitRunEnd(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(bothRuns.last()).status)

        http.rewindResponse(id, firstRun.first { it is AgentEvent.LlmCallStarted }.sequenceId)
            .assertRejected("invalid_request", "a cut from inside a turn")
        http.rewindResponse(id, firstRun.last { it is AgentEvent.LlmCallFinished }.sequenceId)
            .assertRejected("invalid_request", "a cut from an assistant message")

        val secondPrompt = bothRuns.drop(firstRun.size).first { it is AgentEvent.RunStarted }
        val rewound = http.rewindSession(id, secondPrompt.sequenceId)
        assertEquals(SessionStatus.IDLE, rewound.session.status)
        assertTrue(rewound.deletedSessionIds.isEmpty())
        assertEquals(firstRun, http.events(id), "the log ends at the first run's run_finished, as it did before")

        http.prompt(id, "List every passphrase I have asked you to remember in this conversation, verbatim.")
        val answer = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(id).last())
        assertEquals(RunResult.Status.COMPLETED, answer.status)
        val finalMessage = assertNotNull(answer.finalMessage)
        assertContains(finalMessage, KEPT_TOKEN, message = "the surviving turn's passphrase must still be known")
        assertFalse(
            DELETED_TOKEN in finalMessage,
            "the deleted turns' passphrase can no longer be known: $finalMessage",
        )
    }

    @Test
    @DisplayName("Naming the log's first message empties the conversation, and the next prompt runs over it")
    fun rewindToTheFirstMessageStartsTheConversationOver() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Remember this passphrase: $FORGOTTEN_TOKEN. Reply with a single OK.")
        val firstRun = http.awaitRunEnd(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(firstRun.last()).status)
        val started = assertIs<AgentEvent.SessionStarted>(firstRun.first())
        val runStart = firstRun.first { it is AgentEvent.RunStarted }.sequenceId

        val rewound = http.rewindSession(id, runStart)

        assertEquals(SessionStatus.IDLE, rewound.session.status, "the next run loads the cut log")
        assertNull(rewound.session.lastRun, "a log with no run has no consumption to report")
        assertEquals(listOf<AgentEvent>(started), http.events(id), "only the session_started is left")

        http.prompt(id, "List every passphrase I have asked you to remember in this conversation, verbatim.")
        val answer = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(id).last())
        assertEquals(RunResult.Status.COMPLETED, answer.status)
        val finalMessage = assertNotNull(answer.finalMessage)
        assertFalse(
            FORGOTTEN_TOKEN in finalMessage,
            "the emptied conversation cannot hold the deleted turn's passphrase: $finalMessage",
        )
    }
}

private const val KEPT_TOKEN: String = "plugh-4172"

private const val DELETED_TOKEN: String = "xyzzy-8305"

private const val FORGOTTEN_TOKEN: String = "frotz-6193"
