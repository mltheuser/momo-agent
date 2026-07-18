package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import codes.momo.agent.AgentEvent
import codes.momo.agent.ScriptedReply
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.asReply
import codes.momo.agent.assertTwoCleanRuns
import codes.momo.agent.assistantResponse
import codes.momo.agent.baseUrl
import codes.momo.agent.promptSubagentCall
import codes.momo.agent.scriptedServer
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.text
import codes.momo.agent.toolCallResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.deleteExisting
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentSessionTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun subagentHarness(): String =
        writeHarness(tempDir.resolve("harness"), tools = listOf("bash"), subagents = mapOf("self" to "."))
            .toString()

    /** Waits for the spawn to appear on the parent's stream and returns the child's session ID. */
    private suspend fun spawnedChildId(http: HttpClient, rootId: String): String =
        assertIs<AgentEvent.SubagentSpawned>(
            http.streamEvents(rootId, until = { it is AgentEvent.SubagentSpawned }).last().event,
        ).sessionId

    /** An `until` predicate matching the [count]-th RunFinished of the stream. */
    private fun runsFinished(count: Int): (AgentEvent) -> Boolean {
        var finished = 0
        return { it is AgentEvent.RunFinished && ++finished == count }
    }

    /**
     * Runs one scripted spawn on [harness] against a throwaway registry —
     * abandoned unclosed, a process death rather than a clean stop — and
     * returns the stored root and child session IDs.
     */
    private fun spawnStoredByADeadProcess(harness: String, vararg replies: ScriptedReply): Pair<String, String> {
        var rootId = ""
        scriptedServer(*replies).use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                val registry = SessionRegistry(tempDir.resolve("data"), client)
                runBlocking {
                    rootId = registry.create(harness, localWorkspace(tempDir)).id
                    registry.startRun(rootId, "go", TEST_RUN_SETTINGS)
                    registry.awaitRunEnd(rootId)
                }
            }
        }
        val childId = SessionStore(tempDir.resolve("data")).readEvents(rootId)
            .filterIsInstance<AgentEvent.SubagentSpawned>().single().sessionId
        return rootId to childId
    }

    /** Runs [block] after a scripted run in which the root spawned the child "helper" and went idle. */
    private fun withSpawnedChild(block: suspend (http: HttpClient, rootId: String, childId: String) -> Unit) {
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(), localWorkspace(tempDir)).id
            http.prompt(rootId, "go")
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)
            block(http, rootId, childId)
        }
    }

    // ─── Children are sessions ────────────────────────────────────────

    @Test
    @DisplayName("A spawn is on the parent's stream; the child streams live and replays, resolved via its root")
    fun spawnedChildIsAFullSession() {
        val held = ScriptedReply.Held(assistantResponse(finishReason = "stop", text = "the answer is 42"))
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "compute the answer"),
            ).asReply(),
            held,
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ) { http ->
            val root = http.createSession(subagentHarness(), localWorkspace(tempDir))
            http.prompt(root.id, "go")

            // The spawn is visible on the parent's stream, carrying the child's session ID.
            val spawned = assertIs<AgentEvent.SubagentSpawned>(
                http.streamEvents(root.id, until = { it is AgentEvent.SubagentSpawned }).last().event,
            )
            assertEquals("helper", spawned.name)

            // A subscriber mid-run follows the child's events live.
            val midRun = http.streamEvents(spawned.sessionId, until = { it is AgentEvent.RunStarted })
            val started = assertIs<AgentEvent.SessionStarted>(midRun.first().event)
            assertEquals(spawned.sessionId, started.sessionId)
            assertEquals(1, started.depth)
            val running = http.get("/v1/sessions/${spawned.sessionId}").body<SessionInfo>()
            assertEquals(SessionStatus.RUNNING, running.status, "a parent-driven run counts as running")

            val fullRun = coroutineScope {
                val watcher = async { http.streamEvents(spawned.sessionId) }
                held.release()
                watcher.await()
            }
            assertEquals("the answer is 42", assertIs<AgentEvent.RunFinished>(fullRun.last().event).finalMessage)
            http.awaitRunEnd(root.id)

            // Replay serves the same events again.
            assertEquals(fullRun, http.streamEvents(spawned.sessionId))

            // Roots only in the listing; the child resolves by ID through its root's facts.
            assertEquals(listOf(root.id), http.get("/v1/sessions").body<List<SessionInfo>>().map { it.id })
            assertNull(root.parent)
            val child = http.get("/v1/sessions/${spawned.sessionId}").body<SessionInfo>()
            assertEquals(root.id, child.parent)
            assertEquals("helper", child.title)
            // A `self: .` child runs the root's folder — reported canonically.
            assertEquals(Path.of(root.harnessPath).toRealPath().toString(), child.harnessPath)
            assertEquals(root.environment, child.environment)
            assertEquals(SessionStatus.IDLE, child.status)
        }
    }

    @Test
    @DisplayName("A human follow-up prompt reaches an idle child; prompting a running child is a 409")
    fun humanPromptsAChild() {
        val held = ScriptedReply.Held(assistantResponse(finishReason = "stop", text = "vanilla cake baked"))
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "bake a cake"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "Which flavor should it be?").asReply(),
            assistantResponse(finishReason = "stop", text = "helper needs a flavor").asReply(),
            held,
        ) { http ->
            val rootId = http.createSession(subagentHarness(), localWorkspace(tempDir)).id
            http.prompt(rootId, "go")
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)

            // The idle child takes the human's answer in its own conversation.
            http.prompt(childId, "vanilla")
            assertEquals(SessionStatus.RUNNING, http.get("/v1/sessions/$childId").body<SessionInfo>().status)
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)

            val conflict = http.promptResponse(childId, "impatient follow-up")
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("conflict", conflict.body<ApiError>().code)

            held.release()
            http.awaitRunEnd(childId)
            val childLog = http.streamEvents(childId, until = runsFinished(2))
            assertTwoCleanRuns(childLog.map { it.event }, secondUserMessage = "vanilla")
        }
    }

    @Test
    @DisplayName("A three-level tree works over HTTP: nested spawns, whole-tree close, grandchild revival, cascade")
    fun grandchildTreeOverHttp() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "dig deeper"),
            ).asReply(),
            toolCallResponse(
                spawnSubagentCall(id = "call-3", name = "deep"),
                promptSubagentCall(id = "call-4", name = "deep", message = "dig"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "bedrock reached").asReply(),
            assistantResponse(finishReason = "stop", text = "deep says: bedrock").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
            assistantResponse(finishReason = "stop", text = "still bedrock").asReply(),
        ) { http ->
            val root = http.createSession(subagentHarness(), localWorkspace(tempDir))
            http.prompt(root.id, "go")
            val childId = spawnedChildId(http, root.id)
            val grandId = spawnedChildId(http, childId)
            http.awaitRunEnd(root.id)

            // The two-hop parent chain, with the root's facts at every level.
            val child = http.get("/v1/sessions/$childId").body<SessionInfo>()
            val grand = http.get("/v1/sessions/$grandId").body<SessionInfo>()
            assertEquals(root.id, child.parent)
            assertEquals(childId, grand.parent)
            assertEquals("deep", grand.title)
            assertEquals(2, assertIs<AgentEvent.SessionStarted>(http.streamEvents(grandId).first().event).depth)
            assertEquals(listOf(root.id), http.get("/v1/sessions").body<List<SessionInfo>>().map { it.id })

            // Close on the root parks all three levels.
            http.post("/v1/sessions/${root.id}/close")
            listOf(root.id, childId, grandId).forEach { id ->
                assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$id").body<SessionInfo>().status)
            }

            // Prompting the dormant grandchild revives the chain through both hops.
            http.prompt(grandId, "again?")
            http.awaitRunEnd(grandId)
            val revivedTurn = requests.last()
            assertContains(revivedTurn.messages.first().text, "subagent")
            assertEquals("again?", revivedTurn.messages.last().text)
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/${root.id}").body<SessionInfo>().status)

            // Deleting the root removes all three levels.
            http.delete("/v1/sessions/${root.id}")
            listOf(root.id, childId, grandId).forEach { id ->
                assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$id").status)
            }
            assertEquals(emptyList(), SessionStore(tempDir.resolve("data")).sessionIds())
        }
    }

    @Test
    @DisplayName("A typed child's info names the referenced harness folder it runs, resolved hop by hop")
    fun typedChildrenReportTheirOwnHarness() {
        val leaf = writeHarness(tempDir.resolve("leaf"))
        val mid = writeHarness(tempDir.resolve("mid"), subagents = mapOf("leaf" to "../leaf"))
        val rootFolder = writeHarness(tempDir.resolve("harness"), subagents = mapOf("mid" to "../mid"))
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", type = "mid"),
                promptSubagentCall(id = "call-2", name = "helper", message = "dig deeper"),
            ).asReply(),
            toolCallResponse(
                spawnSubagentCall(id = "call-3", name = "deep", type = "leaf"),
                promptSubagentCall(id = "call-4", name = "deep", message = "dig"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "bedrock reached").asReply(),
            assistantResponse(finishReason = "stop", text = "deep says: bedrock").asReply(),
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ) { http ->
            val root = http.createSession(rootFolder.toString(), localWorkspace(tempDir))
            http.prompt(root.id, "go")
            val childId = spawnedChildId(http, root.id)
            val grandId = spawnedChildId(http, childId)
            http.awaitRunEnd(root.id)

            val child = http.get("/v1/sessions/$childId").body<SessionInfo>()
            val grand = http.get("/v1/sessions/$grandId").body<SessionInfo>()
            assertNotEquals(root.harnessPath, child.harnessPath)
            assertEquals(mid.toRealPath().toString(), child.harnessPath)
            assertEquals(leaf.toRealPath().toString(), grand.harnessPath)
            // The environment stays the root's at every level.
            assertEquals(root.environment, child.environment)
            assertEquals(root.environment, grand.environment)
        }
    }

    @Test
    @DisplayName("A child whose stored type the harness no longer declares reports the root's harness folder")
    fun unresolvableChildFallsBackToTheRootHarness() {
        withSpawnedChild { http, rootId, childId ->
            // The harness folder on disk renames the child's type away after the spawn.
            writeHarness(tempDir.resolve("harness"), tools = listOf("bash"), subagents = mapOf("other" to "."))

            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            val rootPath = http.get("/v1/sessions/$rootId").body<SessionInfo>().harnessPath
            assertEquals(rootPath, response.body<SessionInfo>().harnessPath)
        }
    }

    @Test
    @DisplayName("A child whose root harness no longer loads reports the root's stored harness folder")
    fun unloadableRootHarnessFallsBackToTheStoredPath() {
        val mid = writeHarness(tempDir.resolve("mid"))
        val rootFolder = writeHarness(tempDir.resolve("harness"), subagents = mapOf("mid" to "../mid"))
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", type = "mid"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
        ) { http ->
            val root = http.createSession(rootFolder.toString(), localWorkspace(tempDir))
            http.prompt(root.id, "go")
            val childId = spawnedChildId(http, root.id)
            http.awaitRunEnd(root.id)
            val resolved = http.get("/v1/sessions/$childId").body<SessionInfo>().harnessPath
            assertEquals(mid.toRealPath().toString(), resolved, "the type resolves while the root harness loads")

            // The root harness folder loses its manifest after the spawn: Harness.load fails outright.
            rootFolder.resolve("harness.yaml").deleteExisting()

            val response = http.get("/v1/sessions/$childId")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(root.harnessPath, response.body<SessionInfo>().harnessPath)
        }
    }

    @Test
    @DisplayName("A stored spawn without a type — a log predating typed spawning — reports the root's harness folder")
    fun untypedStoredSpawnFallsBackToTheRootHarness() {
        val (rootId, childId) = spawnStoredByADeadProcess(
            subagentHarness(),
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
        )
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
        val harness = subagentHarness()
        val (rootId, childId) = spawnStoredByADeadProcess(
            harness,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
        )
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

    // ─── Restart & revival ────────────────────────────────────────────

    @Test
    @DisplayName("A restarted tree is closed, histories intact; re-prompting the parent revives the child by name")
    fun treeSurvivesARestart() {
        // First server process: the root spawns a typed, model-pinned child whose answer is a question.
        val (rootId, childId) = spawnStoredByADeadProcess(
            subagentHarness(),
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper", type = "self", modelId = "pinned-model"),
                promptSubagentCall(id = "call-2", name = "helper", message = "bake a cake"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "Which flavor should it be?").asReply(),
            assistantResponse(finishReason = "stop", text = "helper needs a flavor").asReply(),
        )

        // Second server process over the same data directory.
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "vanilla")).asReply(),
            assistantResponse(finishReason = "stop", text = "vanilla cake baked").asReply(),
            assistantResponse(finishReason = "stop", text = "cake delivered").asReply(),
        ) { http ->
            // The whole tree restarted closed, histories intact, child fetchable by ID.
            assertEquals(
                SessionStatus.CLOSED,
                http.get("/v1/sessions/$rootId").body<SessionInfo>().status,
            )
            val child = http.get("/v1/sessions/$childId").body<SessionInfo>()
            assertEquals(SessionStatus.CLOSED, child.status)
            assertEquals(rootId, child.parent)
            assertEquals(
                listOf(rootId),
                http.get("/v1/sessions").body<List<SessionInfo>>().map { it.id },
            )
            val history = http.streamEvents(childId)
            assertEquals(
                "Which flavor should it be?",
                assertIs<AgentEvent.RunFinished>(history.last().event).finalMessage,
            )

            // Re-prompting the parent revives the pre-restart child by name...
            http.prompt(rootId, "make it vanilla")
            http.awaitRunEnd(rootId)

            // ...and the revived child continues its prior conversation, still a
            // subagent, still under its spawn-time model override.
            val childTurn = requests[1]
            assertContains(childTurn.messages.first().text, "subagent")
            assertEquals(
                listOf("system", "user", "assistant", "user"),
                childTurn.messages.map { it.role },
            )
            assertEquals("vanilla", childTurn.messages.last().text)
            assertEquals("pinned-model", childTurn.model)
            assertEquals(TEST_RUN_SETTINGS.model, requests[0].model)
            val childLog = http.streamEvents(childId, until = runsFinished(2))
            assertTwoCleanRuns(childLog.map { it.event }, secondUserMessage = "vanilla")
        }
    }

    @Test
    @DisplayName("A stored child missing from its parent's log is a 404 on prompt that leaves no runtime behind")
    fun tornSpawnRecordLeavesNoAttachment() {
        val (rootId, childId) = spawnStoredByADeadProcess(
            subagentHarness(),
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
        )
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
        withSpawnedChild { http, rootId, childId ->
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

    // ─── Rename & favorite through a child ────────────────────────────

    @Test
    @DisplayName("Favoriting through a child's ID marks the tree-wide flag on the root, appending no events")
    fun favoriteThroughAChildMarksTheRoot() {
        withSpawnedChild { http, rootId, childId ->
            val store = SessionStore(tempDir.resolve("data"))
            val logSizes = listOf(rootId, childId).associateWith { store.readEvents(it).size }

            val child = http.setFavorite(childId, true)

            assertTrue(child.favorite)
            assertEquals(SessionStatus.IDLE, child.status, "a favorite toggle must not change the child's status")
            assertTrue(http.get("/v1/sessions/$rootId").body<SessionInfo>().favorite)
            logSizes.forEach { (id, size) ->
                assertEquals(size, store.readEvents(id).size, "favorite is metadata, never an event")
            }
        }
    }

    @Test
    @DisplayName("Renaming through a dormant child's ID retitles just the child, leaving the root's title alone")
    fun renameThroughADormantChild() {
        withSpawnedChild { http, rootId, childId ->
            http.post("/v1/sessions/$rootId/close")

            val renamed = http.renameSession(childId, "diligent helper")

            assertEquals("diligent helper", renamed.title)
            assertEquals(SessionStatus.CLOSED, renamed.status, "a rename must not resume the tree")
            assertEquals("diligent helper", http.get("/v1/sessions/$childId").body<SessionInfo>().title)
            assertEquals("harness", http.get("/v1/sessions/$rootId").body<SessionInfo>().title)
            assertIs<AgentEvent.SessionRenamed>(
                SessionStore(tempDir.resolve("data")).readEvents(childId).last(),
                "a dormant child's rename appends to the child's own log",
            )
        }
    }

    // ─── Cascades ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Close on a child tears the whole tree down; deleting the root removes it and ends child streams")
    fun closeAndDeleteCascade() {
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
            // No further replies: the child's next run hangs in flight.
        ) { http ->
            val rootId = http.createSession(subagentHarness(), localWorkspace(tempDir)).id
            http.prompt(rootId, "go")
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)

            http.prompt(childId, "work away") // Hangs on the LLM: a run in flight.
            assertEquals(SessionStatus.RUNNING, http.get("/v1/sessions/$childId").body<SessionInfo>().status)

            // Closing the CHILD aborts its run and closes the whole tree.
            http.post("/v1/sessions/$childId/close")
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$childId").body<SessionInfo>().status)
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)
            // The aborted run's history is intact up to the abort.
            val childHistory = http.streamEvents(
                childId,
                until = { it is AgentEvent.RunStarted && it.userMessage == "work away" },
            )
            assertTrue(childHistory.isNotEmpty())

            // Deleting the ROOT removes the whole tree and ends the child's live stream.
            withTimeout(STREAM_TIMEOUT_MILLIS) {
                coroutineScope {
                    val connected = CompletableDeferred<Unit>()
                    val watcher = async {
                        var received = 0
                        http.sse("/v1/sessions/$childId/events") {
                            incoming.collect { frame ->
                                if (frame.data != null) received++
                                connected.complete(Unit)
                            }
                        }
                        received // Reached only once the server ends the stream.
                    }
                    connected.await()
                    http.delete("/v1/sessions/$rootId")
                    assertTrue(watcher.await() > 0)
                }
            }
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$rootId").status)
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$childId").status)
            assertEquals(emptyList(), SessionStore(tempDir.resolve("data")).sessionIds())
        }
    }

    @Test
    @DisplayName("A directly deleted child is unknown to a later prompt_subagent and its name is free to reuse")
    fun deletedChildYieldsUnknownName() {
        withScriptedSessionServer(
            tempDir,
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = "get ready"),
            ).asReply(),
            assistantResponse(finishReason = "stop", text = "ready").asReply(),
            assistantResponse(finishReason = "stop", text = "spawned").asReply(),
            toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "still there?")).asReply(),
            toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")).asReply(),
            assistantResponse(finishReason = "stop", text = "respawned").asReply(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(), localWorkspace(tempDir)).id
            http.prompt(rootId, "go")
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)
            val firstRun = http.streamEvents(rootId)

            // Deleting the child directly: its artifacts go, the tree closes, the root survives.
            http.delete("/v1/sessions/$childId")
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$childId").status)
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)

            // The resumed parent finds the name unknown — visible in its log — then free to spawn again.
            http.prompt(rootId, "check on the helper")
            http.awaitRunEnd(rootId)
            val secondRun = http.streamEvents(rootId, afterSequenceId = firstRun.last().id).map { it.event }
            val results = secondRun.filterIsInstance<AgentEvent.ToolCallFinished>()
            assertContains(results.single { it.callId == "call-3" }.resultText, "no subagent named 'helper'")
            assertContains(results.single { it.callId == "call-4" }.resultText, "spawned subagent 'helper'")
            // The re-spawned child is a fresh session under the same name.
            val respawned = secondRun.filterIsInstance<AgentEvent.SubagentSpawned>().single()
            assertNotEquals(childId, respawned.sessionId)
            assertEquals(rootId, http.get("/v1/sessions/${respawned.sessionId}").body<SessionInfo>().parent)
        }
    }
}
