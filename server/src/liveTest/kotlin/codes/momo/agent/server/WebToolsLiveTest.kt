package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.liveSettings
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.withLiveServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebToolsLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "web_search finds the RFC on the picked search model, page_contents saves it to a file with its outline, " +
            "bash reads the file"
    )
    fun searchLoadAndRead() = withLiveServer { http ->
        // The file name hashes the page, so a file left by an earlier run would be kept, not written.
        File(PAGE_DIR).listFiles { file -> file.name.startsWith(PAGE_FILE_PREFIX) }?.forEach { it.delete() }
        val harness = writeHarness(
            tempDir.resolve("harness"),
            tools = listOf("bash", "web_search", "page_contents"),
            instructions = "You are a research assistant. Use your tools as asked " +
                "and keep your final message to a single short sentence.",
        ).toString()
        val id = http.createSession(harness, localWorkspace(tempDir)).id

        val settings = liveSettings.copy(toolModels = liveSettings.toolModels + ("web_search" to SEARCH_MODEL))
        http.prompt(id, PROMPT, settings)

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertEquals(
            SEARCH_MODEL,
            events.filterIsInstance<AgentEvent.RunStarted>().single().settings.toolModels["web_search"],
            "the run records the non-default search model it ran with",
        )
        assertContains(
            events.resultsOf("web_search").joinToString("\n"),
            "rfc9110",
            ignoreCase = true,
            message = "the search answered with the RFC's page",
        )

        val pages = events.resultsOf("page_contents").flatMap { result ->
            Json.parseToJsonElement(result).jsonObject.getValue("results").jsonArray.map { it.jsonObject }
        }
        val page = assertNotNull(pages.find { it.string("url") == RFC_URL }, "the RFC has a result: $pages")
        val path = assertNotNull(page.string("path"), "the RFC was saved to a file: $page")
        val file = File(path)
        assertTrue(file.exists() && path.startsWith("$PAGE_DIR/$PAGE_FILE_PREFIX"), "the file is $path")
        assertEquals(file.length().toInt(), page.getValue("bytes").jsonPrimitive.int, "bytes is the file's size")
        assertTrue(file.length() > ARG_MAX_BYTES, "the page is larger than one command-line argument can be")
        assertContains(file.readText(), "Content-Length", message = "the file holds the page's text")
        assertContains(
            page.getValue("outline").jsonArray.joinToString("\n"),
            "HTTP Semantics",
            message = "the outline lists the RFC's title heading",
        )
        assertNotNull(page.string("outline_note"), "the RFC has more headings than the outline shows")
        val unloadable = assertNotNull(pages.find { it.string("url") == UNLOADABLE_URL }, "the dead page is reported")
        assertNotNull(unloadable.string("error"), "the unloadable page carries an error: $unloadable")
        assertTrue(
            events.filterIsInstance<AgentEvent.ToolCallStarted>()
                .any { it.toolName == "bash" && path in it.arguments.toString() },
            "a bash command read the saved file",
        )
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

private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

private const val SEARCH_MODEL: String = "instant:cloud@exa"

private const val PAGE_DIR: String = "/tmp/momo-web"

private const val PAGE_FILE_PREFIX: String = "rfc-editor.org-rfc-rfc9110-"

private const val RFC_URL: String = "https://www.rfc-editor.org/rfc/rfc9110"

private const val UNLOADABLE_URL: String = "https://nonexistent.invalid/"

private const val ARG_MAX_BYTES: Long = 128 * 1024

private const val PROMPT: String =
    "First, call web_search with the query \"RFC 9110 HTTP Semantics\" and max_results 3. " +
        "Then call page_contents once with exactly these two URLs: $RFC_URL and $UNLOADABLE_URL — " +
        "the second one cannot load; that is expected, do not retry it. " +
        "Then use bash to find the line of the saved RFC file that defines the Content-Length header field, " +
        "and reply with one sentence naming that line number."
