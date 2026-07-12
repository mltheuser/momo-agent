package codes.momo.agent

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Asserts [events] record exactly two clean completed runs — the
 * conversational shape where the first run ends with a question and
 * [secondUserMessage] answers it — with gapless sequence IDs.
 */
public fun assertTwoCleanRuns(events: List<AgentEvent>, secondUserMessage: String) {
    val starts = events.withIndex().filter { it.value is AgentEvent.RunStarted }.map { it.index }
    val finishes = events.withIndex().filter { it.value is AgentEvent.RunFinished }.map { it.index }
    assertEquals(2, starts.size, "two sends mean two RunStarted events")
    assertEquals(2, finishes.size, "every run must finish")
    assertTrue(finishes[0] < starts[1], "the first run must finish before the second starts")
    assertEquals(events.lastIndex, finishes.last(), "the second RunFinished closes the log")
    finishes.forEach { index ->
        assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(events[index]).status)
    }
    assertEquals(secondUserMessage, assertIs<AgentEvent.RunStarted>(events[starts[1]]).userMessage)
    assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId }, "sequence IDs must be gapless")
}
