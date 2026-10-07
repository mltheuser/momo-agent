package codes.momo.agent.server.cut

import ai.router.sdk.chat.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunSettings
import codes.momo.agent.SelectionPatch
import codes.momo.agent.server.storage.InvalidRewindPointException
import codes.momo.agent.server.storage.LogLine
import codes.momo.agent.server.storage.encodeLogLine
import codes.momo.agent.server.storage.parseLogLine
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CutPolicyTest {

    private val log = listOf(
        AgentEvent.SessionStarted(
            0,
            0,
            sessionId = "s",
            title = "s",
            harnessPath = null,
            workspace = "/work",
            settings = null
        ),
        AgentEvent.RunStarted(1, 1, userMessage = "first", settings = SETTINGS),
        AgentEvent.SessionRenamed(2, 2, title = "renamed"),
        AgentEvent.SelectionChanged(3, 3, SelectionPatch(toolModels = mapOf("web_search" to "fast:cloud@exa"))),
        AgentEvent.RunStarted(5, 5, userMessage = "second", settings = SETTINGS),
        AgentEvent.ToolCallStarted(6, 6, callId = "c", toolName = "bash", arguments = JsonObject(emptyMap())),
    )

    @Test
    @DisplayName("The cut point names the first deleted event; the last survivor is the closest earlier event")
    fun lastSurvivorIsTheClosestEarlierEvent() {
        assertEquals(3L, log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = 5))
        assertEquals(0L, log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = 1))
    }

    @Test
    @DisplayName("A cut starts at a user message only: unknown, preserved, opening and in-turn events are refused")
    fun invalidCutPointsAreRefused() {
        listOf<Long>(4, 3, 2, 6, 0).forEach { named ->
            assertFailsWith<InvalidRewindPointException>("cut from $named") {
                log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = named)
            }
        }
    }

    @Test
    @DisplayName("A cut keeps the renames and selection changes after it and drops every other later event")
    fun aCutPreservesRenamesAndSelections() {
        val lines = log.map { parseLogLine(encodeLogLine(it)) }
        val survivors = lines.filter { it.survivesCut(lastSurvivingSequenceId = 1) }
        assertEquals(listOf(0L, 1L, 2L, 3L), survivors.map(LogLine::sequenceId))
        assertTrue(log.filter { it.sequenceId > 1 }.all { it.isPreservedByACut() == (it.sequenceId in 2L..3L) })
    }
}

private val SETTINGS = RunSettings("m", ReasoningEffort.NONE, emptyMap())
