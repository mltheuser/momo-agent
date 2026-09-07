package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.session.SessionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

        http.awaitRunEnd(id)

        val namedAt = firstRun.last { it.event is AgentEvent.LlmCallFinished }.id

        val survivors = firstRun.map { it.id }.filter { it < namedAt }
        val cutPoint = survivors.last()

        val rewound = coroutineScope {
            val watcher = async {
                http.streamEvents(id, afterSequenceId = preCutMax) { it is AgentEvent.ConversationRewound }
            }
            val rewound = http.rewindSession(id, namedAt)
            val received = watcher.await()
            assertEquals(
                preCutMax + 1,
                received.single().id,
                "the parked subscriber's open stream receives the announcement, and nothing else",
            )
            assertIs<AgentEvent.ConversationRewound>(received.single().event)
            rewound
        }

        assertEquals(SessionStatus.IDLE, rewound.session.status, "a beheaded run reads as ended, the tree attached")
        assertTrue(rewound.deletedSessionIds.isEmpty())

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

        val rewound = http.rewindSession(id, runStart)

        assertEquals(SessionStatus.IDLE, rewound.session.status, "the tree stays attached, reloaded from the cut log")
        assertNull(rewound.session.lastRun, "a log with no run has no consumption to report")
        val replay = http.streamEvents(id, until = { it is AgentEvent.ConversationRewound })
        val tail = assertIs<AgentEvent.ConversationRewound>(replay.last().event)
        assertEquals(started.id, tail.lastSurvivingSequenceId, "the session_started is the cut point")
        assertEquals(listOf(started.id, tail.sequenceId), replay.map { it.id }, "no run_started is left at all")

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

private const val KEPT_TOKEN: String = "plugh-4172"

private const val DELETED_TOKEN: String = "xyzzy-8305"

private const val FORGOTTEN_TOKEN: String = "frotz-6193"
