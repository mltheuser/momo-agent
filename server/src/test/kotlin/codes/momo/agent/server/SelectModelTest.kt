package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlm
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.assistantResponse
import codes.momo.agent.forModel
import codes.momo.agent.fromRootAgent
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.toolCallResponse
import codes.momo.agent.usableCatalog
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The select-model endpoint and the `modelSelection` a session's info
 * derives from its log: a `model_selected` pick and a `run_started` record
 * compete by sequence ID, a child falls back to its spawn's pin, and the
 * event is user metadata a rewind preserves like the title.
 */
class SelectModelTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Recording ────────────────────────────────────────────────────

    @Test
    @DisplayName("Selecting on a live session logs model_selected through the agent and shows in the info")
    fun selectOnALiveSessionLogsAndShows() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
            val created = http.createSession(harnessPath(tempDir), localWorkspace(tempDir))
            assertNull(created.modelSelection, "a fresh log names no selection")

            val selected = http.selectModel(created.id, "picked-model", ReasoningEffort.HIGH)

            assertEquals(ModelSelection("picked-model", ReasoningEffort.HIGH), selected.modelSelection)
            val event = assertIs<AgentEvent.ModelSelected>(
                http.streamEvents(created.id, until = { it is AgentEvent.ModelSelected }).last().event,
            )
            assertEquals("picked-model", event.model)
            assertEquals(ReasoningEffort.HIGH, event.reasoningEffort)

            // The agent emitted it, so the next run numbers on gaplessly.
            http.prompt(created.id, "go")
            http.awaitRunEnd(created.id)
            sessionStore(tempDir).readEvents(created.id).forEachIndexed { index, logged ->
                assertEquals(index.toLong(), logged.sequenceId, "the run numbers on past the selection")
            }
        }
    }

    @Test
    @DisplayName("Selecting on a closed session appends to its stored log, stays closed, and a resume numbers on")
    fun selectOnAClosedSessionAppendsWithoutResuming() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.post("/v1/sessions/$id/close")

            val selected = http.selectModel(id, "picked-model")

            assertEquals(SessionStatus.CLOSED, selected.status, "a selection must not resume the session")
            assertEquals(ModelSelection("picked-model"), selected.modelSelection)
            val appended = assertIs<AgentEvent.ModelSelected>(sessionStore(tempDir).readEvents(id).last())
            assertEquals(1L, appended.sequenceId, "appended gaplessly after session_started")
            assertNull(appended.reasoningEffort, "no effort asks for the provider default")

            http.prompt(id, "go")
            http.awaitRunEnd(id)
            sessionStore(tempDir).readEvents(id).forEachIndexed { index, logged ->
                assertEquals(index.toLong(), logged.sequenceId, "the resumed run numbers on past the selection")
            }
        }
    }

    // ─── Derivation ───────────────────────────────────────────────────

    @Test
    @DisplayName("The latest by sequence ID wins: a run updates the selection, a later pick overrides it")
    fun latestOfPickAndRunWins() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.selectModel(id, "picked-first")

            http.prompt(id, "go")
            http.awaitRunEnd(id)
            assertEquals(
                ModelSelection(TEST_RUN_SETTINGS.model),
                http.sessionInfo(id).modelSelection,
                "run_started updates the shown selection to what actually ran",
            )

            val overridden = http.selectModel(id, "picked-later", ReasoningEffort.LOW)
            assertEquals(ModelSelection("picked-later", ReasoningEffort.LOW), overridden.modelSelection)
        }
    }

    @Test
    @DisplayName("A child spawned with pinned model and effort shows both before its own log names anything")
    fun spawnPinBacksAChildWithoutOwnSelection() {
        // The catalog backs the pin's validation; the pinned id is one of its
        // fully-qualified model strings, the only form a spawn accepts.
        val fake = FakeLlm(
            usableCatalog(PINNED_MODEL),
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(
                        id = "call-1",
                        name = "helper",
                        modelId = PINNED_MODEL,
                        reasoningEffort = ReasoningEffort.LOW,
                    ),
                ),
                saying = SPAWN_PROMPT,
            ).fromRootAgent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "spawned")).fromRootAgent(),
        )
        withFakeSessionServer(tempDir, fake) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)

            assertEquals(
                ModelSelection(PINNED_MODEL, ReasoningEffort.LOW),
                http.sessionInfo(childId).modelSelection,
                "the spawn's pins back a child log naming no selection of its own",
            )
        }
    }

    @Test
    @DisplayName("A parent-driven run makes the inherited model visible in the child's own selection")
    fun drivenRunShowsInTheChildSelection() {
        withSpawnedChild(tempDir) { http, _, childId ->
            assertEquals(
                ModelSelection(TEST_RUN_SETTINGS.model),
                http.sessionInfo(childId).modelSelection,
                "the driven run's run_started carries the inherited model",
            )
        }
    }

    // ─── Rewind ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A selection deep in the deleted range survives a rewind, keeping its own sequence ID")
    fun rewindPreservesTheSelection() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "one"), saying = "first"),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "two"), saying = "second"),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "first question")
            http.awaitRunEnd(id)
            http.prompt(id, "second question")
            http.awaitRunEnd(id)
            http.selectModel(id, "picked-model", ReasoningEffort.MEDIUM)
            val store = sessionStore(tempDir)
            val events = store.readEvents(id)
            val selectedAt = assertIs<AgentEvent.ModelSelected>(events.last()).sequenceId
            val secondRunStart = events.filterIsInstance<AgentEvent.RunStarted>().last().sequenceId

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(ModelSelection("picked-model", ReasoningEffort.MEDIUM), rewound.session.modelSelection)
            val kept = assertIs<AgentEvent.ModelSelected>(
                store.readEvents(id).single { it is AgentEvent.ModelSelected },
                "the selection stands above the cut",
            )
            assertEquals(selectedAt, kept.sequenceId, "a preserved line is never renumbered")
        }
    }

    @Test
    @DisplayName("A rewind deleting the latest run_started falls the derivation back to the older pick")
    fun rewindFallsBackToTheOlderPick() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "one"), saying = "first"),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "two"), saying = "second")
                .forModel("second-model"),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "first question")
            http.awaitRunEnd(id)
            http.selectModel(id, "picked-model")
            http.prompt(id, "second question", model = "second-model")
            http.awaitRunEnd(id)
            assertEquals(
                ModelSelection("second-model"),
                http.sessionInfo(id).modelSelection,
                "the second run's run_started is the latest contributor before the cut",
            )
            val secondRunStart = sessionStore(tempDir).readEvents(id)
                .filterIsInstance<AgentEvent.RunStarted>().last().sequenceId

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(
                ModelSelection("picked-model"),
                rewound.session.modelSelection,
                "the deleted run_started stops contributing; the older pick stands",
            )
        }
    }

    @Test
    @DisplayName("Naming a model_selected as the cut point is a 400: what a cut keeps cannot be what it cuts from")
    fun namingASelectionAsTheCutPointIsRejected() {
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "done")),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            http.awaitRunEnd(id)
            http.selectModel(id, "picked-model")
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)
            val selected = assertIs<AgentEvent.ModelSelected>(before.last())

            val response = http.rewindResponse(id, selected.sequenceId)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals(before, store.readEvents(id), "a rejected rewind must not have touched the log")
        }
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A blank model is a 400 invalid_request leaving the log untouched")
    fun blankModelIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val before = sessionStore(tempDir).readEvents(id)

            val response = http.selectModelResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals(before, sessionStore(tempDir).readEvents(id))
            assertNull(http.sessionInfo(id).modelSelection)
        }
    }

    @Test
    @DisplayName("A body without a model field is a 400 invalid_request, like a blank one")
    fun missingModelIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.post("/v1/sessions/$id/select-model") {
                setBody(TextContent("""{"reasoningEffort": "high"}""", ContentType.Application.Json))
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertNull(http.sessionInfo(id).modelSelection)
        }
    }

    @Test
    @DisplayName("A select-model on an unknown session is a 404 unknown_session")
    fun unknownSessionIs404() {
        withSessionServer(tempDir) { http ->
            val response = http.selectModelResponse("no-such-id", "some-model")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("unknown_session", response.body<ApiError>().code)
        }
    }
}

/** The spawn-time model pin, as a fully-qualified catalog string — the only form a spawn accepts. */
private const val PINNED_MODEL: String = "pinned-model:cloud@fake-provider"
