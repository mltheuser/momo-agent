package codes.momo.agent

import ai.router.sdk.chat.ReasoningEffort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionTest {

    private fun started(settings: RunSettings?) = AgentEvent.SessionStarted(
        sequenceId = 0,
        timestampMillis = 0,
        sessionId = "s",
        title = "s",
        harnessPath = null,
        workspace = "/work",
        settings = settings,
    )

    private fun picked(seq: Long, patch: SelectionPatch) = AgentEvent.SelectionChanged(seq, seq, patch)

    private fun ran(seq: Long, settings: RunSettings) = AgentEvent.RunStarted(seq, seq, "go", settings)

    @Test
    @DisplayName("A root starts with nothing selected; a chat pick alone makes no tool model, and no run settings")
    fun aRootStartsUnset() {
        assertEquals(SessionSelection.NONE, listOf(started(null)).selection())
        val toolOnly = listOf(started(null), picked(1, SelectionPatch(toolModels = mapOf(SEARCH to FAST))))
        assertEquals(SessionSelection(null, mapOf(SEARCH to FAST)), toolOnly.selection())
        assertNull(toolOnly.selection().runSettings(), "no run settings while the chat model is unset")
    }

    @Test
    @DisplayName(
        "A child starts with its spawn-time settings; picks merge: chat replaced if given, tool entries merged"
    )
    fun picksMergeOverTheSpawnCopy() {
        val log = listOf(
            started(RunSettings("m", ReasoningEffort.LOW, mapOf(SEARCH to FAST, CONTENTS to AUTO))),
            picked(1, SelectionPatch(toolModels = mapOf(SEARCH to INSTANT))),
            picked(2, SelectionPatch(chat = ChatSelection("n", ReasoningEffort.HIGH))),
        )
        assertEquals(
            RunSettings("n", ReasoningEffort.HIGH, mapOf(SEARCH to INSTANT, CONTENTS to AUTO)),
            log.selection().runSettings(),
        )
    }

    @Test
    @DisplayName("A run's settings merge in too: they never drop a tool entry the run did not carry")
    fun aRunMergesItsSettings() {
        val log = listOf(
            started(null),
            picked(1, SelectionPatch(toolModels = mapOf(CONTENTS to AUTO))),
            ran(2, RunSettings("m", ReasoningEffort.NONE, mapOf(SEARCH to FAST))),
        )
        assertEquals(
            SessionSelection(ChatSelection("m", ReasoningEffort.NONE), mapOf(CONTENTS to AUTO, SEARCH to FAST)),
            log.selection(),
        )
    }

    @Test
    @DisplayName("A pick a cut preserved past later runs still wins over the earlier runs it follows in the log")
    fun aPreservedPickFollowsTheSurvivingRuns() {
        val log = listOf(
            started(null),
            ran(1, RunSettings("m", ReasoningEffort.LOW, mapOf(SEARCH to FAST))),
            picked(9, SelectionPatch(toolModels = mapOf(SEARCH to INSTANT))),
        )
        assertEquals(mapOf(SEARCH to INSTANT), log.selection().toolModels)
    }
}

private const val SEARCH = "web_search"
private const val CONTENTS = "page_contents"
private const val FAST = "fast:cloud@exa"
private const val INSTANT = "instant:cloud@exa"
private const val AUTO = "auto:cloud@exa"
