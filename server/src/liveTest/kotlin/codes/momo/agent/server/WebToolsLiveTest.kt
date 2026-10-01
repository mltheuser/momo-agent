package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.withLiveServer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebToolsLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("web_search finds the RFC, page_contents loads it and reports the unloadable page in place")
    fun searchThenLoad() = withLiveServer { http ->
        val harness = writeHarness(
            tempDir.resolve("harness"),
            tools = listOf("web_search", "page_contents"),
            instructions = "You are a research assistant. Use your web tools as asked " +
                "and keep your final message to a single short sentence.",
        ).toString()
        val id = http.createSession(harness, localWorkspace(tempDir)).id

        http.prompt(id, PROMPT)

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        val searched = events.resultsOf("web_search")
        assertTrue(searched.isNotEmpty(), "the model called web_search")
        assertContains(
            searched.joinToString("\n"),
            "rfc2119",
            ignoreCase = true,
            message = "the search answered with the RFC's page",
        )
        val loaded = events.resultsOf("page_contents").joinToString("\n")
        assertContains(loaded, "SHALL NOT", message = "the loaded page's text came back")
        assertContains(loaded, UNLOADABLE_HOST, message = "the unloadable page is reported, not dropped")
    }
}

private fun List<AgentEvent>.resultsOf(toolName: String): List<String> {
    val callIds = filterIsInstance<AgentEvent.ToolCallStarted>().filter { it.toolName == toolName }.map { it.callId }
    return filterIsInstance<AgentEvent.ToolCallFinished>().filter { it.callId in callIds }.map { finished ->
        assertEquals(
            AgentEvent.ToolCallFinished.Outcome.SUCCESS,
            finished.outcome,
            "a $toolName call succeeds even when a page fails: ${finished.resultText}",
        )
        finished.resultText
    }
}

private const val UNLOADABLE_HOST: String = "nonexistent.invalid"

private const val PROMPT: String =
    "First, call web_search with the query \"RFC 2119 requirement levels\" and max_results 3. " +
        "Then call page_contents once with exactly these two URLs: https://www.rfc-editor.org/rfc/rfc2119 " +
        "and https://$UNLOADABLE_HOST/ — the second one cannot load; that is expected, do not retry it. " +
        "Then reply with one sentence naming the document's title."
