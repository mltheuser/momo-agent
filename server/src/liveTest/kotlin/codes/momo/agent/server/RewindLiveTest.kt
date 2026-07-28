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
import kotlin.test.assertTrue

/**
 * The canonical rewind path against a real model: a multi-run session cut
 * back to its first run, promptable again at once — and the deleted turn
 * gone from what the model can know, pinned by a planted token that exists
 * nowhere but in the deleted turn.
 */
class RewindLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A rewound session ends at the named event, reads idle, and no longer knows the deleted turns")
    fun rewindForgetsTheDeletedTurns() = withLiveServer { http ->
        val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Remember this passphrase: $KEPT_TOKEN. Reply with a single OK.")
        val firstRun = http.streamEvents(id)
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(firstRun.last().event).status)
        val cutId = firstRun.last().id

        http.prompt(id, "Here is a second passphrase to remember: $DELETED_TOKEN. Reply with a single OK.")
        val secondRun = http.streamEvents(id, afterSequenceId = cutId)
        val preCutMax = secondRun.last().id

        val rewound = http.rewindSession(id, cutId)

        assertEquals(SessionStatus.IDLE, rewound.session.status, "the tree stays attached, reloaded from the cut log")
        assertTrue(rewound.deletedSessionIds.isEmpty())
        // The stored log ends at the named event plus the rewind's own
        // announcement, numbered above everything deleted.
        val replay = http.streamEvents(id, until = { it is AgentEvent.ConversationRewound })
        val tail = assertIs<AgentEvent.ConversationRewound>(replay.last().event)
        assertEquals(cutId, tail.lastSurvivingSequenceId)
        assertEquals(preCutMax + 1, tail.sequenceId)
        assertEquals(cutId, replay[replay.size - 2].id)

        // Promptable immediately — and the deleted passphrase exists nowhere
        // the model can reach, while the kept one is still in its transcript.
        http.prompt(id, "List every passphrase I have asked you to remember in this conversation, verbatim.")
        val answer = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = tail.sequenceId).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, answer.status)
        val finalMessage = assertNotNull(answer.finalMessage)
        assertContains(finalMessage, KEPT_TOKEN, message = "the surviving turn's passphrase must still be known")
        assertFalse(
            DELETED_TOKEN in finalMessage,
            "the deleted turn's passphrase can no longer be known: $finalMessage",
        )
    }
}

/** Planted in the surviving first run: the rewound transcript's proof of continuity. */
private const val KEPT_TOKEN: String = "plugh-4172"

/** Planted only in the deleted second run: after the rewind it exists nowhere the model can reach. */
private const val DELETED_TOKEN: String = "xyzzy-8305"
