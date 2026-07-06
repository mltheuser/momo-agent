package codes.momo.agent.tool

import codes.momo.agent.Budgets
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ToolResultTest {

    @Test
    @DisplayName("Success text is the tool output verbatim")
    fun successTextIsVerbatim() {
        assertEquals("all good", ToolResult.Success("all good").text)
    }

    @Test
    @DisplayName("Error text carries an explicit textual error signal plus the message")
    fun errorTextCarriesSignal() {
        val text = ToolResult.Error("file not found: notes.txt").text

        assertTrue(text.startsWith("Error:"), "expected a textual error signal, was: $text")
        assertContains(text, "file not found: notes.txt")
    }

    @Test
    @DisplayName("TimedOut text names the budget and reads as an error")
    fun timeoutTextNamesBudget() {
        val text = ToolResult.TimedOut().text

        assertTrue(text.startsWith("Error:"), "expected a textual error signal, was: $text")
        assertContains(text, "timed out")
        assertContains(text, Budgets.TOOL_TIMEOUT.toString())
    }

    @Test
    @DisplayName("TimedOut text names the actual bound applied when it differs from the tool budget")
    fun timeoutTextNamesActualBound() {
        assertContains(ToolResult.TimedOut(timeout = 42.seconds).text, "timed out after 42s")
    }

    @Test
    @DisplayName("TimedOut text attributes a bound below the tool budget to the run's wall clock")
    fun timeoutTextAttributesWallClockBound() {
        assertContains(ToolResult.TimedOut(timeout = 42.seconds).text, "wall-clock budget")
        assertFalse(
            ToolResult.TimedOut().text.contains("wall-clock"),
            "the full tool budget must not be attributed to the wall clock",
        )
    }

    @Test
    @DisplayName("TimedOut text includes partial output when present, and omits the section when absent")
    fun timeoutTextCarriesPartialOutputOnlyWhenPresent() {
        assertContains(ToolResult.TimedOut(partialOutput = "compiled 3 of 9 files").text, "compiled 3 of 9 files")
        assertFalse(
            ToolResult.TimedOut().text.contains("Partial output"),
            "a timeout without partial output must not render an empty partial-output section",
        )
    }
}
