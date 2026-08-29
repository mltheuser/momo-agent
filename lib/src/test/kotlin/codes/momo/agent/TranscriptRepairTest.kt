package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptRepairTest {

    private fun message(role: String): ChatMessage =
        ChatMessage(role = role, content = listOf(ContentPart(type = ContentPartType.TEXT, text = "text")))

    private fun assistantCalling(vararg callIds: String, tool: String = "bash"): ChatMessage = ChatMessage(
        role = "assistant",
        content = emptyList(),
        toolCalls = callIds.map { ToolCall(id = it, function = ToolCallFunction(tool, buildJsonObject { })) },
    )

    private fun repair(
        transcript: List<ChatMessage>,
        startedCallIds: Set<String> = emptySet(),
        runStatus: RunResult.Status? = null,
    ): List<ChatMessage> = toolCallRepairs(transcript, startedCallIds, runStatus)

    private fun toolResult(callId: String): ChatMessage = ChatMessage(
        role = "tool",
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = "done")),
        toolCallId = callId,
    )

    @Test
    @DisplayName("A transcript without tool calls needs no repair")
    fun transcriptWithoutToolCallsNeedsNoRepair() {
        assertTrue(repair(emptyList()).isEmpty())
        assertTrue(repair(listOf(message("system"), message("user"), message("assistant"))).isEmpty())
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

        assertTrue(repair(transcript).isEmpty())
    }

    @Test
    @DisplayName("Unanswered trailing tool calls get synthesized aborted results, in call order")
    fun unansweredCallsGetAbortedResults() {
        val transcript = listOf(message("system"), message("user"), assistantCalling("a", "b"))

        val repairs = repair(transcript)

        assertEquals(listOf("a", "b"), repairs.map { it.toolCallId })
        repairs.forEach { synthesized ->
            assertEquals("tool", synthesized.role)
            assertEquals(toolCallRepairText("bash", started = false, runStatus = null), synthesized.text)
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

        assertEquals(listOf("b", "c"), repair(transcript).map { it.toolCallId })
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

        assertEquals(listOf("b"), repair(transcript).map { it.toolCallId })
    }

    @Test
    @DisplayName("The synthesized text names what ended the run and whether the call had started")
    fun synthesizedTextNamesTheCutAndReceipt() {
        val transcript = listOf(message("system"), message("user"), assistantCalling("a", "b"))

        val texts = repair(transcript, startedCallIds = setOf("a"), runStatus = RunResult.Status.STOPPED)
            .map { it.content.single().text.orEmpty() }

        assertContains(texts[0], "a user stopped the run while this call was executing")
        assertContains(texts[1], "a user stopped the run before this call could execute")
        texts.forEach { assertContains(it, "Error: ") }
    }

    @Test
    @DisplayName("For a prompt_subagent call the text says whether the subagent received the message")
    fun promptRepairsNameWhetherTheSubagentReceivedTheMessage() {
        val transcript =
            listOf(message("system"), message("user"), assistantCalling("a", "b", tool = "prompt_subagent"))

        val texts = repair(transcript, startedCallIds = setOf("a"), runStatus = RunResult.Status.ERROR)
            .map { it.content.single().text.orEmpty() }

        assertContains(texts[0], "the run failed while the subagent was working on this message")
        assertContains(texts[0], "prompting it again continues that conversation")
        assertContains(texts[1], "the run failed before this call could execute")
        assertContains(texts[1], "never received this message; prompt it again to deliver it")
    }
}
