package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptRepairTest {

    private fun message(role: String): ChatMessage =
        ChatMessage(role = role, content = listOf(ContentPart(type = ContentPartType.TEXT, text = "text")))

    private fun assistantCalling(vararg callIds: String): ChatMessage = ChatMessage(
        role = "assistant",
        content = emptyList(),
        toolCalls = callIds.map { ToolCall(id = it, function = ToolCallFunction("bash", buildJsonObject { })) },
    )

    private fun toolResult(callId: String): ChatMessage = ChatMessage(
        role = "tool",
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = "done")),
        toolCallId = callId,
    )

    @Test
    @DisplayName("A transcript without tool calls needs no repair")
    fun transcriptWithoutToolCallsNeedsNoRepair() {
        assertTrue(abortedToolResults(emptyList()).isEmpty())
        assertTrue(
            abortedToolResults(listOf(message("system"), message("user"), message("assistant"))).isEmpty(),
        )
    }

    @Test
    @DisplayName("A fully answered trailing turn needs no repair")
    fun answeredTrailingTurnNeedsNoRepair() {
        val transcript = listOf(
            message("system"),
            message("user"),
            assistantCalling("a", "b"),
            toolResult("a"),
            toolResult("b"),
        )

        assertTrue(abortedToolResults(transcript).isEmpty())
    }

    @Test
    @DisplayName("Unanswered trailing tool calls get synthesized aborted results, in call order")
    fun unansweredCallsGetAbortedResults() {
        val transcript = listOf(message("system"), message("user"), assistantCalling("a", "b"))

        val repairs = abortedToolResults(transcript)

        assertEquals(listOf("a", "b"), repairs.map { it.toolCallId })
        repairs.forEach { repair ->
            assertEquals("tool", repair.role)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, repair.content.single().text)
        }
    }

    @Test
    @DisplayName("A partially answered trailing turn is repaired only for the missing calls")
    fun partiallyAnsweredTurnRepairsOnlyTheMissingCalls() {
        val transcript = listOf(
            message("system"),
            message("user"),
            assistantCalling("a", "b", "c"),
            toolResult("a"),
        )

        assertEquals(listOf("b", "c"), abortedToolResults(transcript).map { it.toolCallId })
    }

    @Test
    @DisplayName("Answered earlier turns do not mask the dangling trailing one")
    fun earlierTurnsDoNotMaskTheTrailingOne() {
        val transcript = listOf(
            message("system"),
            message("user"),
            assistantCalling("a"),
            toolResult("a"),
            assistantCalling("b"),
        )

        assertEquals(listOf("b"), abortedToolResults(transcript).map { it.toolCallId })
    }
}
