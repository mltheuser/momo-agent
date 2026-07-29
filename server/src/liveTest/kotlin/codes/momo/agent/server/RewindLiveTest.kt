package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
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

/**
 * The rewind against a real model, at both ends of a log: a multi-run session
 * cut from an assistant message mid-run — the arrow's ordinary target — and a
 * session cut back to nothing but its `session_started`. Both are promptable
 * again at once, and what the cut deleted is gone from what the model can
 * know — pinned by planted tokens that exist nowhere but in the deleted turns.
 */
class RewindLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A rewind from an assistant message cuts mid-run, reads idle, and no longer knows the deleted turns")
    fun rewindForgetsTheDeletedTurns() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Remember this passphrase: $KEPT_TOKEN — I will ask you to repeat it later.")
        val firstRun = http.streamEvents(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(firstRun.last().event).status)
        val firstRunEnd = firstRun.last().id

        http.prompt(id, "A second passphrase to remember: $DELETED_TOKEN — I may ask for that one too.")
        val secondRun = http.streamEvents(id, afterSequenceId = firstRunEnd)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(secondRun.last().event).status)
        val preCutMax = secondRun.last().id
        // The registry drops its claim on a run only after the frame ending it,
        // so a rewind racing that frame draws a 409 rather than the 200 below.
        http.awaitRunEnd(id)

        // The arrow's ordinary target is the reply a user is looking at, so the
        // case names the first run's *final* assistant message — the one it
        // has whether or not the model reached for a tool. That deletes the
        // reply, the run's own completion with it, and the whole second turn
        // above it: the run is left to the announcement to close.
        val namedAt = firstRun.last { it.event is AgentEvent.LlmCallFinished }.id
        // Derived, never assumed: what sits directly below an assistant message
        // is whatever the model's turn put there.
        val survivors = firstRun.map { it.id }.filter { it < namedAt }
        val cutPoint = survivors.last()

        val rewound = http.rewindSession(id, namedAt)

        assertEquals(SessionStatus.IDLE, rewound.session.status, "a beheaded run reads as ended, the tree attached")
        assertTrue(rewound.deletedSessionIds.isEmpty())
        // The stored log ends just below the named event, plus the rewind's
        // own announcement, numbered above everything deleted.
        val replay = http.streamEvents(id, until = { it is AgentEvent.ConversationRewound })
        val tail = assertIs<AgentEvent.ConversationRewound>(replay.last().event)
        assertEquals(cutPoint, tail.lastSurvivingSequenceId)
        assertEquals(preCutMax + 1, tail.sequenceId, "the announcement is numbered above everything deleted")
        assertEquals(survivors + tail.sequenceId, replay.map { it.id }, "the log ends at the event below the named one")
        assertTrue(
            replay.none { it.event is AgentEvent.RunFinished },
            "the cut took the run's own completion: only the announcement closes it",
        )
        assertEquals(
            1,
            replay.count { it.event is AgentEvent.RunStarted },
            "the second turn went with the range above the named event",
        )

        // Promptable immediately over the beheaded run — and the deleted
        // passphrase exists nowhere the model can reach, while the surviving
        // turn's is still in its transcript.
        http.prompt(id, "List every passphrase I have asked you to remember in this conversation, verbatim.")
        val answer = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = tail.sequenceId).last().event,
        )
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
        val firstRun = http.streamEvents(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(firstRun.last().event).status)
        val started = firstRun.first()
        assertIs<AgentEvent.SessionStarted>(started.event)
        val runStart = firstRun.first { it.event is AgentEvent.RunStarted }.id

        // The topmost message the arrow ever sits on: naming it deletes every
        // conversational event the log holds.
        val rewound = http.rewindSession(id, runStart)

        assertEquals(SessionStatus.IDLE, rewound.session.status, "the tree stays attached, reloaded from the cut log")
        assertNull(rewound.session.lastRun, "a log with no run has no consumption to report")
        val replay = http.streamEvents(id, until = { it is AgentEvent.ConversationRewound })
        val tail = assertIs<AgentEvent.ConversationRewound>(replay.last().event)
        assertEquals(started.id, tail.lastSurvivingSequenceId, "the session_started is the cut point")
        assertEquals(listOf(started.id, tail.sequenceId), replay.map { it.id }, "no run_started is left at all")

        // A real turn over a log holding no conversation — the reload path
        // this case exists for — and the deleted passphrase is unreachable.
        http.prompt(id, "List every passphrase I have asked you to remember in this conversation, verbatim.")
        val answer = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = tail.sequenceId).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, answer.status)
        val finalMessage = assertNotNull(answer.finalMessage)
        assertFalse(
            FORGOTTEN_TOKEN in finalMessage,
            "the emptied conversation cannot hold the deleted turn's passphrase: $finalMessage",
        )
    }
}

/** Planted in the first run's user message, which stands below the cut: the rewound transcript's continuity. */
private const val KEPT_TOKEN: String = "plugh-4172"

/** Planted in the second turn, wholly inside the deleted range: after the rewind the model cannot reach it. */
private const val DELETED_TOKEN: String = "xyzzy-8305"

/** Planted in the only run of the session cut back to nothing: the emptied conversation's proof. */
private const val FORGOTTEN_TOKEN: String = "frotz-6193"
