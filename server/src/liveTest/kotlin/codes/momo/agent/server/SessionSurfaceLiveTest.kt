package codes.momo.agent.server

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's HTTP surface short of a chat completion, against the real
 * server process: the session lifecycle and its change stream, every 400
 * and 404 the routes answer, the dormant-session writes, the catalog proxy
 * and the templates. None of it reaches a model, so these cases cost the
 * suite milliseconds and share its one process — each owning only what it
 * creates.
 */
class SessionSurfaceLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Lifecycle: create, get, list, close, delete — each mutation signalling the change stream once")
    fun lifecycleWithTheChangeStream() = withLiveServer { http ->
        val harness = harnessPath(tempDir)
        val workspace = localWorkspace(tempDir, "lifecycle")

        http.withChangeStream { stream ->
            // One mutation at a time, each awaited before the next: a signal
            // is a hint to re-read, so two raised together may arrive as
            // one — and a signal that stopped arriving, or arrives twice,
            // fails here rather than cancelling out in a total.
            val first = http.createSession(harness, workspace)
            stream.awaitFrames(2)
            val second = http.createSession(harness, workspace)
            stream.awaitFrames(3)
            assertNotEquals(first.id, second.id)
            assertTrue(first.id.isNotBlank())
            assertEquals("harness", first.title, "the title defaults to the harness folder's name")
            assertEquals(harness, first.harnessPath)
            assertEquals(workspace, first.environment)
            assertEquals(SessionStatus.IDLE, first.status)
            assertNull(first.parent, "a root has no parent")
            assertNull(first.lastRun, "no run happened yet")
            assertNull(first.modelSelection, "nothing selected yet")
            assertTrue(first.createdAtMillis > 0)
            assertEquals(first, http.sessionInfo(first.id))
            assertEquals(setOf(first, second), http.sessions(workspace).toSet(), "get and list agree")

            // A stop with nothing in flight is an idempotent no-op that attaches nothing.
            val stopped = http.stopResponse(first.id)
            assertEquals(HttpStatusCode.OK, stopped.status, stopped.bodyAsText())
            assertEquals(SessionStatus.IDLE, stopped.body<SessionInfo>().status)

            // Reads signal nothing: their frames would fill the count below early.
            http.sessions(workspace)
            http.sessionInfo(first.id)

            // Close parks: still listed, idempotent, the neighbour untouched —
            // and a close whose caller is gone still signals.
            http.abandonedClose(first.id)
            stream.awaitFrames(4)
            val closed = http.sessionInfo(first.id)
            assertEquals(SessionStatus.CLOSED, closed.status)
            assertEquals(closed, http.sessions(workspace).single { it.id == first.id })
            assertEquals(SessionStatus.IDLE, http.sessionInfo(second.id).status, "sessions close independently")
            val closedAgain = http.closeResponse(first.id)
            assertEquals(HttpStatusCode.OK, closedAgain.status)
            assertEquals(SessionStatus.CLOSED, closedAgain.body<SessionInfo>().status)
            stream.awaitFrames(5)
            val whileClosed = http.stopResponse(first.id)
            assertEquals(SessionStatus.CLOSED, whileClosed.body<SessionInfo>().status, "a stop does not attach")

            http.deleteSession(first.id)
            stream.awaitFrames(6)
            val lookup = http.sessionInfoResponse(first.id)
            assertEquals(HttpStatusCode.NotFound, lookup.status)
            assertEquals("unknown_session", lookup.body<ApiError>().code)
            assertEquals(listOf(second.id), http.sessions(workspace).map { it.id }, "a deleted session leaves the list")
            assertFalse(
                sharedLiveServer.dataDir.resolve("sessions/${first.id}").isDirectory(),
                "a deleted session leaves the store",
            )
        }
    }

    @Test
    @DisplayName("Validation is a 400 invalid_request (or a named code) on every surface, changing nothing")
    fun validationIsA400Everywhere() = withLiveServer { http ->
        val harness = harnessPath(tempDir)
        val workspace = localWorkspace(tempDir)
        val missing = tempDir.resolve("no-such-folder").toString()

        // Create.
        http.createSessionResponse(CreateSessionRequest(missing, workspace))
            .assertRejected("invalid_harness", "a missing harness folder", names = missing)
        val broken = writeHarness(tempDir.resolve("broken"), subagents = mapOf("helper" to "../nowhere")).toString()
        http.createSessionResponse(CreateSessionRequest(broken, workspace))
            .assertRejected("invalid_harness", "a broken subagent reference", names = "'helper'")
        http.createSessionResponse(CreateSessionRequest(harness, EnvironmentSpec.Local(missing)))
            .assertRejected("invalid_environment", "a missing workspace folder", names = missing)
        http.rawCreateSessionResponse(
            """{"harnessPath": ${Json.encodeToString(
                harness
            )}, "environment": {"type": "martian", "workspace": "/tmp"}}""",
        ).assertRejected("invalid_request", "an unknown environment type")
        // A well-formed privilege value, so this pins the absence of the
        // field: a client still asking for a posture is told, instead of
        // quietly getting whatever the host has.
        http.rawCreateSessionResponse(
            """{"harnessPath": ${Json.encodeToString(harness)}, "environment": {"type": "local", """ +
                """"workspace": ${Json.encodeToString(workspace.workspace)}, "privilege": "passwordless_sudo"}}""",
        ).assertRejected("invalid_request", "a declared privilege")

        val id = http.createSession(harness, workspace).id
        val before = http.streamEvents(id, until = { it is AgentEvent.SessionStarted })

        // Prompt.
        http.promptResponse(id, "   ").assertRejected("invalid_request", "a blank prompt")
        http.promptResponse(id, "go", model = "   ").assertRejected("invalid_request", "a blank model")
        http.rawPromptResponse(id, """{"prompt": "go"}""").assertRejected("invalid_request", "a missing model")
        http.rawPromptResponse(id, """{"prompt": "go", "model": "m", "reasoningEffort": "ultra"}""")
            .assertRejected("invalid_request", "an unknown reasoning effort")

        // Rename and select-model.
        http.renameResponse(id, "   ").assertRejected("invalid_request", "a blank title")
        http.selectModelResponse(id, "   ").assertRejected("invalid_request", "a blank model selection")
        http.rawSelectModelResponse(id, """{"reasoningEffort": "high"}""")
            .assertRejected("invalid_request", "a selection without a model")

        // Rewind: the first event, a preserved event, an absent ID, malformed bodies.
        http.rewindResponse(id, before.first().id).assertRejected("invalid_request", "naming the session_started")
        http.renameSession(id, "Renamed, then named as a cut point")
        val renamed = http.streamEvents(id, afterSequenceId = before.last().id) { it is AgentEvent.SessionRenamed }
        http.rewindResponse(id, renamed.last().id).assertRejected("invalid_request", "naming a preserved event")
        http.rewindResponse(id, renamed.last().id + 100).assertRejected("invalid_request", "an absent sequence ID")
        listOf("{}", """{"firstDeletedSequenceId": "not-a-number"}""").forEach { body ->
            http.rawRewindResponse(id, body).assertRejected("invalid_request", "rewind body '$body'")
        }

        // Workspace parameter.
        http.sessionsResponse(workspace = null).assertRejected("invalid_request", "an unscoped listing")
        http.sessionsResponse(workspace = "relative/path").assertRejected("invalid_request", "a relative workspace")

        // Nothing above touched the log beyond the one rename this case made.
        val after = http.streamEvents(id, until = { it is AgentEvent.SessionRenamed })
        assertEquals(before + renamed, after, "a rejected request leaves the log as it was")
        assertEquals("Renamed, then named as a cut point", http.sessionInfo(id).title)
    }

    @Test
    @DisplayName(
        "Unknown is a 404 unknown_session on every route, a foreign workspace included; one folder is one scope"
    )
    fun unknownAndForeignSessionsAre404() = withLiveServer { http ->
        val unknown = "no-such-id"
        mapOf(
            "get" to http.sessionInfoResponse(unknown),
            "prompt" to http.promptResponse(unknown, "hello"),
            "rename" to http.renameResponse(unknown, "title"),
            "select-model" to http.selectModelResponse(unknown, "m"),
            "rewind" to http.rewindResponse(unknown, 0),
            "retry" to http.retryResponse(unknown),
            "stop" to http.stopResponse(unknown),
            "close" to http.closeResponse(unknown),
            "delete" to http.deleteResponse(unknown),
            "events" to http.eventsResponse(unknown),
        ).forEach { (route, response) ->
            assertEquals(HttpStatusCode.NotFound, response.status, route)
            assertEquals("unknown_session", response.body<ApiError>().code, route)
        }

        val projectA = localWorkspace(tempDir, "scoped-a")
        val projectB = localWorkspace(tempDir, "scoped-b")
        val inA = http.createSession(harnessPath(tempDir), projectA).id
        val inB = http.createSession(harnessPath(tempDir), projectB).id
        assertEquals(listOf(inA), http.sessions(projectA).map { it.id }, "the listing is scoped to its workspace")
        val foreign = http.sessionInfoResponse(inB, projectA.workspace)
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("unknown_session", foreign.body<ApiError>().code, "a foreign session must look nonexistent")
        assertEquals(HttpStatusCode.OK, http.sessionInfoResponse(inA, projectA.workspace).status, "own scope")

        listOf("${projectA.workspace}/", "${projectA.workspace}/.", "${projectA.workspace}/../scoped-a")
            .forEach { spelling ->
                assertEquals(listOf(inA), http.sessions(spelling).map { it.id }, "spelling: $spelling")
            }
    }

    @Test
    @DisplayName(
        "Rename and select-model reach a live and a closed session alike: the parked subscriber gets the frame"
    )
    fun renameAndSelectModelOnLiveAndClosedSessions() = withLiveServer { http ->
        val live = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "live")).id
        val closed = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "closed")).id
        http.closeSession(closed)

        for ((id, expectedStatus) in listOf(live to SessionStatus.IDLE, closed to SessionStatus.CLOSED)) {
            coroutineScope {
                // Subscribed first — dormant or not — so the tail has to serve the appended events.
                val watcher = async { http.streamEvents(id, until = { it is AgentEvent.ModelSelected }) }
                val renamed = http.renameSession(id, "Chosen title")
                assertEquals("Chosen title", renamed.title)
                assertEquals(expectedStatus, renamed.status, "a rename must neither resume nor park: $id")
                val selected = http.selectModel(id, "picked-model", ReasoningEffort.HIGH)
                assertEquals(ModelSelection("picked-model", ReasoningEffort.HIGH), selected.modelSelection)
                assertEquals(expectedStatus, selected.status, "a selection must neither resume nor park: $id")

                val appended = watcher.await().map { it.event }.drop(1)
                assertEquals("Chosen title", assertIs<AgentEvent.SessionRenamed>(appended[0]).title)
                val event = assertIs<AgentEvent.ModelSelected>(appended[1])
                assertEquals("picked-model" to ReasoningEffort.HIGH, event.model to event.reasoningEffort)
                assertEquals(listOf(1L, 2L), appended.map { it.sequenceId }, "appended gaplessly after session_started")
            }
            val info = http.sessionInfo(id)
            assertEquals("Chosen title", info.title)
            assertEquals(ModelSelection("picked-model", ReasoningEffort.HIGH), info.modelSelection)
            assertEquals(expectedStatus, info.status)
        }
        assertEquals("Chosen title", http.sessions(localWorkspace(tempDir, "closed")).single().title, "listed too")
    }

    @Test
    @DisplayName(
        "Privilege is reported while attached and null when closed; corrupt stored state is loud, never poison"
    )
    fun privilegeAndCorruptState() = withLiveServer { http ->
        // Which posture the host grants is its business — CI, a root
        // container and a NOPASSWD dev box all differ — so only its
        // presence is asserted, never its value.
        val workspace = localWorkspace(tempDir, "shared")
        val healthy = http.createSession(harnessPath(tempDir), workspace)
        assertNotNull(healthy.privilege, "a built environment must report the posture it found")

        // One workspace, so the corrupt session is inside the very listing
        // the healthy one is read from: skipped for being unreadable, not
        // for being somebody else's. Scoped to a session this case owns, so
        // the shared process stays sound for every other case.
        val corrupt = http.createSession(harnessPath(tempDir), workspace)
        sharedLiveServer.dataDir.resolve("sessions/${corrupt.id}/session.json").writeText("not json")
        try {
            assertEquals(listOf(healthy.id), http.sessions(workspace).map { it.id }, "the corrupt one is skipped")
            val lookup = http.sessionInfoResponse(corrupt.id)
            assertEquals(HttpStatusCode.InternalServerError, lookup.status)
            assertEquals("corrupt_session", lookup.body<ApiError>().code)
        } finally {
            // Unconditionally, so a failed assertion cannot leave the corrupt
            // session to the cases sharing this process — and this is the
            // assertion that unreadable metadata is still deletable.
            http.deleteSession(corrupt.id)
        }
        assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(corrupt.id).status)

        assertNull(http.closeSession(healthy.id).privilege, "with no environment built there is nothing to report")
    }

    @Test
    @DisplayName("GET /v1/models serves the running router's catalog, filtered to what an agent run can use")
    fun modelsServeTheUsableCatalog() = withLiveServer { http ->
        val response = http.modelsResponse()
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        // ai-router's shape survives the proxy: envelope plus snake_case item fields.
        assertContains(body, "\"provider_type\"")
        val served = response.body<ModelList>()
        assertEquals("list", served.`object`)
        assertContains(
            served.data.map { it.model },
            liveChatModel,
            "the model this tier converses with must survive the route's capability filter",
        )
        served.data.forEach { model ->
            assertTrue(
                model.hasCapability(Capability.CHAT) && model.hasCapability(Capability.TOOLS),
                "an agent run needs both chat and tools: $model",
            )
        }
    }

    @Test
    @DisplayName(
        "Templates: PUT lands as a file, the list sorts, GET reads back, a blank body is 400, a missing name 404"
    )
    fun templates() = withLiveServer { http ->
        // Names unique to this case: the shared process's template store is global.
        val review = "surface-review"
        val bugfix = "surface-bugfix"
        val file = sharedLiveServer.dataDir.resolve("templates/$review.md")

        assertEquals(TemplateResponse(review, "Review this diff."), http.putTemplate(review, "Review this diff."))
        assertTrue(file.isRegularFile(), "the template lands as $file")
        assertEquals("Review this diff.", file.readText())
        http.putTemplate(bugfix, "Fix the bug.")
        assertEquals(listOf(bugfix, review), http.templateNames().filter { it.startsWith("surface-") }, "sorted")

        val read = http.templateResponse(review)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(TemplateResponse(review, "Review this diff."), read.body())
        http.putTemplate(review, "Review this diff carefully.")
        assertEquals("Review this diff carefully.", file.readText(), "a second PUT overwrites")

        http.putTemplateResponse(review, "   ").assertRejected("invalid_request", "a blank template body")
        assertEquals("Review this diff carefully.", file.readText(), "a rejected body leaves the file alone")

        val missing = http.templateResponse("surface-no-such-template")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("unknown_template", missing.body<ApiError>().code)
    }
}

/** Asserts a 400 carrying [code], the message naming [names] when given; [what] says which rejection this is. */
private suspend fun HttpResponse.assertRejected(code: String, what: String, names: String? = null) {
    assertEquals(HttpStatusCode.BadRequest, status, "$what: ${bodyAsText()}")
    val error = body<ApiError>()
    assertEquals(code, error.code, what)
    if (names != null) assertContains(error.message, names, message = what)
}
