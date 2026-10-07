package codes.momo.agent.server

import ai.router.sdk.ModelList
import ai.router.sdk.search.SearchModel
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.SelectionPatch
import codes.momo.agent.SessionSelection
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.rig.assertRejected
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.createSessionResponse
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.liveSettings
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.promptResponse
import codes.momo.agent.server.rig.select
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.toolModelsResponse
import codes.momo.agent.server.rig.withLiveServer
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolModelsLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "Tool models: the detail lists the tree's tools taking a model, a prompt must cover them, " +
            "a pick round-trips through the detail and a run records the models it ran with"
    )
    fun toolModelsAcrossTheTree() = withLiveServer { http ->
        writeHarness(tempDir.resolve("researcher"), tools = listOf("bash", "page_contents", "web_search"))
        val root = writeHarness(tempDir.resolve("root"), subagents = mapOf("researcher" to "../researcher"))
        val id = http.createSession(root.toString(), localWorkspace(tempDir)).id

        assertEquals(
            listOf("web_search", "page_contents"),
            http.sessionInfo(id).toolsWithModel,
            "a bash-only root lists its subagent's tools, in catalog order",
        )

        val before = http.events(id)
        http.promptResponse(id, "go", liveSettings.copy(toolModels = mapOf("web_search" to SEARCH_FAST)))
            .assertRejected("missing_tool_model", "a prompt without a page_contents model", names = "page_contents")
        assertEquals(before, http.events(id), "a refused prompt leaves the log as it was")

        http.select(id, SelectionPatch(toolModels = mapOf("web_search" to SEARCH_INSTANT)))
        http.select(id, SelectionPatch(toolModels = mapOf("page_contents" to CONTENTS_AUTO)))
        assertEquals(
            SessionSelection(chat = null, toolModels = TOOL_MODELS),
            http.sessionInfo(id).selection,
            "tool picks merge and leave the chat slot unset",
        )

        val settings = liveSettings.copy(toolModels = TOOL_MODELS)
        http.prompt(id, "Without using any tools or subagents, reply with the single word: ok.", settings)
        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertEquals(settings, events.filterIsInstance<AgentEvent.RunStarted>().single().settings)
        assertEquals(settings, http.sessionInfo(id).selection.runSettings(), "the run's settings are the selection")
    }

    @Test
    @DisplayName("GET /v1/tools/{tool}/models serves the router's catalog for the tool; a tool without one is a 404")
    fun toolModelCatalogs() = withLiveServer { http ->
        val response = http.toolModelsResponse("web_search")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "\"provider_type\"", message = "the router's own snake_case shape")
        assertContains(response.body<ModelList<SearchModel>>().data.map { it.model }, SEARCH_INSTANT)
        assertEquals(HttpStatusCode.OK, http.toolModelsResponse("page_contents").status)

        listOf("bash", "no_such_tool").forEach { tool ->
            http.toolModelsResponse(tool)
                .assertRejected("unknown_tool", "$tool has no model catalog", status = HttpStatusCode.NotFound)
        }
    }

    @Test
    @DisplayName("An unknown tool in a subagent harness fails the create")
    fun aBrokenSubagentHarnessFailsTheCreate() = withLiveServer { http ->
        writeHarness(tempDir.resolve("helper"), tools = listOf("bash", "no_such_tool"))
        val parent = writeHarness(tempDir.resolve("parent"), subagents = mapOf("helper" to "../helper"))
        http.createSessionResponse(CreateSessionRequest(parent.toString(), localWorkspace(tempDir)))
            .assertRejected("invalid_harness", "an unknown tool in a subagent harness", names = "no_such_tool")
    }
}

private val TOOL_MODELS: Map<String, String> = mapOf("web_search" to SEARCH_INSTANT, "page_contents" to CONTENTS_AUTO)

private const val SEARCH_FAST: String = "fast:cloud@exa"

private const val SEARCH_INSTANT: String = "instant:cloud@exa"

private const val CONTENTS_AUTO: String = "auto:cloud@exa"
