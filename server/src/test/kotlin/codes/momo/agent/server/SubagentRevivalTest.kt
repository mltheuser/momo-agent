package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlm
import codes.momo.agent.FakeLlmRule
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.harness.writeHarness
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a server makes of a child it cannot resolve: which harness folder its
 * info can still name, and when reviving it fails instead. Every case here
 * stages what no live run could leave behind — a rewritten stored log, or a
 * harness folder that drifted under a tree already spawned from it.
 */
class SubagentRevivalTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Runs one spawning run on [harness] against a throwaway registry —
     * abandoned unclosed, a process death rather than a clean stop — and
     * returns the stored root and child session IDs.
     */
    private fun spawnStoredByADeadProcess(harness: String, vararg rules: FakeLlmRule): Pair<String, String> =
        withFakeLlm(FakeLlm(*rules)) { client ->
            val registry = SessionRegistry(tempDir.resolve("data"), client)
            val rootId = runBlocking {
                val id = registry.create(harness, localWorkspace(tempDir)).id
                registry.startRun(id, SPAWN_PROMPT, TEST_RUN_SETTINGS)
                registry.awaitRunEnd(id)
                id
            }
            val childId = SessionStore(tempDir.resolve("data")).readEvents(rootId)
                .filterIsInstance<AgentEvent.SubagentSpawned>().single().sessionId
            rootId to childId
        }

    // ─── Resolving a stored child ─────────────────────────────────────

    @Test
    @DisplayName("A stored spawn without a type — a log predating typed spawning — reports the root's harness folder")
    fun untypedStoredSpawnFallsBackToTheRootHarness() {
        val (rootId, childId) = spawnStoredByADeadProcess(subagentHarness(tempDir), *spawnHelperRules())
        // The stored parent log is rewritten in the pre-typed-spawning format: its spawn carries no type.
        val store = SessionStore(tempDir.resolve("data"))
        tempDir.resolve("data/sessions/$rootId/events.jsonl").writeText(
            store.readEvents(rootId).joinToString("\n", postfix = "\n") { event ->
                Json.encodeToString(
                    if (event is AgentEvent.SubagentSpawned) event.copy(type = null, modelId = null) else event,
                )
            },
        )
        assertNull(store.readEvents(rootId).filterIsInstance<AgentEvent.SubagentSpawned>().single().type)

        withSessionServer(tempDir) { http ->
            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            val rootPath = http.get("/v1/sessions/$rootId").body<SessionInfo>().harnessPath
            assertEquals(rootPath, response.body<SessionInfo>().harnessPath)
        }
    }

    @Test
    @DisplayName("A child whose parent's stored log is corrupt reports the root's stored harness folder")
    fun corruptParentLogFallsBackToTheRootHarness() {
        val harness = subagentHarness(tempDir)
        val (rootId, childId) = spawnStoredByADeadProcess(harness, *spawnHelperRules())
        // Garbage above the log's tail corrupts the whole stored log — reading it throws.
        val parentLog = tempDir.resolve("data/sessions/$rootId/events.jsonl")
        parentLog.writeText(
            parentLog.readLines().joinToString("\n", postfix = "\n") { line ->
                if ("subagent_spawned" in line) "garbage" else line
            },
        )
        assertFailsWith<CorruptSessionException> { SessionStore(tempDir.resolve("data")).readEvents(rootId) }

        withSessionServer(tempDir) { http ->
            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(harness, response.body<SessionInfo>().harnessPath)
        }
    }

    @Test
    @DisplayName("A child whose type the harness no longer declares reports the root's stored harness folder")
    fun undeclaredTypeFallsBackToTheRootHarness() {
        withSpawnedChild(tempDir) { http, rootId, childId ->
            // The harness folder on disk renames the child's type away, so the
            // hop to the child's own harness has nothing left to follow.
            writeHarness(tempDir.resolve("harness"), tools = listOf("bash"), subagents = mapOf("other" to "."))

            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            val rootPath = http.get("/v1/sessions/$rootId").body<SessionInfo>().harnessPath
            assertEquals(rootPath, response.body<SessionInfo>().harnessPath)
        }
    }

    @Test
    @DisplayName("A child whose root harness no longer loads at all reports that root's stored harness folder")
    fun unloadableRootHarnessFallsBackToItsStoredPath() {
        withSpawnedChild(tempDir) { http, rootId, childId ->
            // A manifest that no longer parses: the harness the hops start from cannot be loaded.
            tempDir.resolve("harness/harness.yaml").writeText("tools: [unclosed\n")
            assertFailsWith<HarnessValidationException> { Harness.load(tempDir.resolve("harness")) }

            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            val rootPath = http.get("/v1/sessions/$rootId").body<SessionInfo>().harnessPath
            assertEquals(rootPath, response.body<SessionInfo>().harnessPath)
        }
    }

    // ─── Revival ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A stored child missing from its parent's log is a 404 on prompt that leaves no runtime behind")
    fun tornSpawnRecordLeavesNoAttachment() {
        val (rootId, childId) = spawnStoredByADeadProcess(subagentHarness(tempDir), *spawnHelperRules())
        // A process death can tear the spawn line off the parent's log
        // after the child's folder exists; the child becomes unreachable.
        val parentLog = tempDir.resolve("data/sessions/$rootId/events.jsonl")
        parentLog.writeText(
            parentLog.readLines().filterNot { "subagent_spawned" in it }.joinToString("\n", postfix = "\n"),
        )

        withSessionServer(tempDir) { http ->
            val response = http.promptResponse(childId, "hello?")
            assertEquals(HttpStatusCode.NotFound, response.status)
            // The failed prompt tore down the runtime it attached.
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("Prompting a dormant child whose type the harness no longer declares is a 409 unrevivable_subagent")
    fun undeclaredTypeAnswersConflictOnDirectPrompt() {
        withSpawnedChild(tempDir) { http, rootId, childId ->
            http.post("/v1/sessions/$rootId/close")
            // The harness folder on disk renames the child's type away before the tree is rebuilt.
            writeHarness(tempDir.resolve("harness"), tools = listOf("bash"), subagents = mapOf("other" to "."))

            val response = http.promptResponse(childId, "hello?")

            assertEquals(HttpStatusCode.Conflict, response.status)
            val error = response.body<ApiError>()
            assertEquals("unrevivable_subagent", error.code)
            assertContains(error.message, "which the harness no longer declares")
            // The failed prompt tore down the runtime it attached.
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)
        }
    }
}
