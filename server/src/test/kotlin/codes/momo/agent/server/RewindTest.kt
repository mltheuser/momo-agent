package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.assistantResponse
import codes.momo.agent.bashCall
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.harness.writeHarness
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.toolCallResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rewind endpoint on a single session: the cut and its announcement,
 * the rename it keeps regardless, the streams surviving the shrunken file,
 * the cut's edges — naming the log's last event, naming its first message,
 * and the two IDs no cut may name (its first event, and an event a cut
 * preserves) — the rest of the error surface, and the truncated log as the
 * resume and restart source of truth.
 */
class RewindTest {

    @TempDir
    lateinit var tempDir: Path

    /** One scripted completion per run; each case prompts with the matching question. */
    private fun threeRunRules() = arrayOf(
        onOpeningTurn(assistantResponse(finishReason = "stop", text = "one"), saying = "first"),
        onOpeningTurn(assistantResponse(finishReason = "stop", text = "two"), saying = "second"),
        onOpeningTurn(assistantResponse(finishReason = "stop", text = "three"), saying = "third"),
    )

    /**
     * Runs the "first" and "second" prompts to completion and reports the
     * boundaries the cases cut at. Each run is awaited on registry state
     * rather than on its `RunFinished` frame — the in-flight claim outlives
     * that frame, so a case rewinding on its heels would draw a 409 — and the
     * boundaries are then read off the stored log.
     */
    private suspend fun HttpClient.twoRuns(id: String): TwoRuns {
        prompt(id, "first question")
        awaitRunEnd(id)
        prompt(id, "second question")
        awaitRunEnd(id)
        val events = sessionStore(tempDir).readEvents(id)
        val runStarts = events.filterIsInstance<AgentEvent.RunStarted>()
        return TwoRuns(
            firstRunEnd = events.first { it is AgentEvent.RunFinished }.sequenceId,
            secondRunStart = runStarts.last().sequenceId,
            preCutMax = events.last().sequenceId,
            firstRunStart = runStarts.first().sequenceId,
        )
    }

    // ─── The cut ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Rewinding an attached session cuts the log, announces it, and leaves the session promptable")
    fun rewindCutsTheLogAndStaysPromptable() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunStart, preCutMax) = http.twoRuns(id)

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(
                SessionStatus.IDLE,
                rewound.session.status,
                "the runtime stays attached — reloaded, not closed",
            )
            assertTrue(rewound.deletedSessionIds.isEmpty())
            val events = sessionStore(tempDir).readEvents(id)
            val tail = assertIs<AgentEvent.ConversationRewound>(events.last())
            assertEquals(firstRunEnd, tail.lastSurvivingSequenceId)
            assertEquals(preCutMax + 1, tail.sequenceId, "the tail is numbered above the log's pre-cut maximum")
            assertEquals(
                firstRunEnd,
                events[events.size - 2].sequenceId,
                "the named event went with the cut; the event below it is the last survivor",
            )
            assertEquals(rewound.session.updatedAtMillis, tail.timestampMillis)

            // Promptable the moment the call returns, resuming the cut log.
            http.prompt(id, "third question")
            http.awaitRunEnd(id)
            val resumed = sessionStore(tempDir).readEvents(id)
            assertIs<AgentEvent.RunFinished>(resumed.last())
            assertEquals(1, resumed.count { it is AgentEvent.ConversationRewound })
            assertEquals(
                tail.sequenceId + 1,
                resumed[resumed.indexOfFirst { it is AgentEvent.ConversationRewound } + 1].sequenceId,
                "the next run continues directly above the rewound tail",
            )
        }
    }

    @Test
    @DisplayName("A rename deep in the deleted range survives the cut, keeping its own sequence ID")
    fun rewindPastARenameKeepsTheTitle() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val title = "Named after the second run"
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart, preCutMax) = http.twoRuns(id)
            http.renameSession(id, title)
            val renamedAt = http.streamEvents(id, afterSequenceId = preCutMax) {
                it is AgentEvent.SessionRenamed
            }.last().id
            assertTrue(renamedAt > secondRunStart + 1, "the rename sits deep in the deleted range: $renamedAt")

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(title, rewound.session.title, "a rewind edits the conversation, not the title")
            val events = sessionStore(tempDir).readEvents(id)
            assertIs<AgentEvent.ConversationRewound>(events.last())
            val kept = assertIs<AgentEvent.SessionRenamed>(
                events[events.size - 2],
                "the rename stands above the cut, under the tail",
            )
            assertEquals(renamedAt, kept.sequenceId, "a preserved line is never renumbered")
            assertEquals(1, events.count { it is AgentEvent.RunStarted }, "the conversation was still cut")

            // And the derived title outlives the reload the rewind performed.
            assertEquals(title, http.sessionInfo(id).title)
        }
    }

    @Test
    @DisplayName("Rewinding a closed session stays closed; the next prompt resumes from the truncated log")
    fun rewindingAClosedSessionStaysClosed() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart) = http.twoRuns(id)
            http.closeSession(id)

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(SessionStatus.CLOSED, rewound.session.status, "a rewind never attaches a runtime")
            val store = sessionStore(tempDir)
            assertEquals(1, store.readEvents(id).count { it is AgentEvent.RunStarted })

            http.prompt(id, "third question")
            http.awaitRunEnd(id)
            val events = store.readEvents(id)
            assertEquals(2, events.count { it is AgentEvent.RunStarted })
            assertTrue(
                events.zipWithNext().all { (before, after) -> before.sequenceId < after.sequenceId },
                "the resumed run numbers on above the gap: ${events.map { it.sequenceId }}",
            )
        }
    }

    @Test
    @DisplayName("A restarted server loads the truncated log and continues above the inert gap")
    fun truncatedLogSurvivesARestart() {
        var id = ""
        var rewoundTailId = -1L
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart) = http.twoRuns(id)
            http.rewindSession(id, secondRunStart)
            rewoundTailId = sessionStore(tempDir).readEvents(id).last().sequenceId
        }

        // A second server process over the same data directory.
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            assertEquals(SessionStatus.CLOSED, http.sessionInfo(id).status)
            http.prompt(id, "third question")
            http.awaitRunEnd(id)
            val resumed = sessionStore(tempDir).readEvents(id)
            assertIs<AgentEvent.RunFinished>(resumed.last())
            assertEquals(
                rewoundTailId + 1,
                resumed[resumed.indexOfFirst { it.sequenceId == rewoundTailId } + 1].sequenceId,
                "restoration continues the sequence above the rewound tail",
            )
        }
    }

    // ─── Streams over the shrunken file ───────────────────────────────

    @Test
    @DisplayName("A subscriber parked at the log's end receives the conversation_rewound on its open stream")
    fun parkedSubscriberReceivesTheRewind() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunStart, preCutMax) = http.twoRuns(id)

            coroutineScope {
                // Parked past everything stored: the only event it can ever
                // receive is one the rewind appends past the shrunken file.
                val watcher = async {
                    http.streamEvents(id, afterSequenceId = preCutMax) { it is AgentEvent.ConversationRewound }
                }
                http.rewindSession(id, secondRunStart)

                val received = watcher.await()
                val tail = assertIs<AgentEvent.ConversationRewound>(received.single().event)
                assertEquals(firstRunEnd, tail.lastSurvivingSequenceId)
                assertEquals(preCutMax + 1, received.single().id)
            }
        }
    }

    @Test
    @DisplayName("A Last-Event-ID reconnect from inside the deleted range converges on the conversation_rewound")
    fun reconnectFromInsideTheDeletedRangeConverges() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart, preCutMax) = http.twoRuns(id)
            http.rewindSession(id, secondRunStart)

            // The named event is itself deleted, so a reconnect resuming from
            // it sits inside the range the cut took.
            val insideDeletedRange = secondRunStart.also { assertTrue(it < preCutMax) }
            val replay = http.streamEvents(id, afterSequenceId = insideDeletedRange) {
                it is AgentEvent.ConversationRewound
            }

            assertIs<AgentEvent.ConversationRewound>(replay.single().event, "nothing but the tail lies above the cut")

            // A full replay serves the survivors plus the tail, each frame
            // carrying its own stored sequence ID.
            val full = http.streamEvents(id) { it is AgentEvent.ConversationRewound }
            assertEquals(
                sessionStore(tempDir).readEvents(id).map { it.sequenceId },
                full.map { it.id },
            )
        }
    }

    @Test
    @DisplayName("A reconnect above the cut serves the preserved rename first, the conversation_rewound last")
    fun reconnectServesThePreservedRenameBeforeTheTail() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val title = "Renamed above the cut"
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunStart, preCutMax) = http.twoRuns(id)
            http.renameSession(id, title)
            http.rewindSession(id, secondRunStart)

            val insideDeletedRange = secondRunStart.also { assertTrue(it < preCutMax) }
            val replay = http.streamEvents(id, afterSequenceId = insideDeletedRange) {
                it is AgentEvent.ConversationRewound
            }

            assertEquals(2, replay.size, "the preserved rename and the tail both lie above the cut: $replay")
            val renamed = assertIs<AgentEvent.SessionRenamed>(replay.first().event, "the rename is served first")
            assertEquals(title, renamed.title)
            assertTrue(renamed.sequenceId > firstRunEnd, "a preserved frame keeps its own ID, above the cut")
            assertIs<AgentEvent.ConversationRewound>(replay.last().event, "the announcement is served last")
        }
    }

    // ─── The cut's edges and the error surface ────────────────────────

    @Test
    @DisplayName("Naming the log's last event deletes exactly it: every valid cut takes at least the event it names")
    fun namingTheLastEventDeletesJustThatEvent() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "first question")
            http.awaitRunEnd(id)
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)
            val lastId = before.last().sequenceId

            val rewound = http.rewindSession(id, lastId)

            assertTrue(rewound.deletedSessionIds.isEmpty())
            val events = store.readEvents(id)
            val tail = assertIs<AgentEvent.ConversationRewound>(events.last())
            assertEquals(before[before.size - 2].sequenceId, tail.lastSurvivingSequenceId)
            assertEquals(before.dropLast(1) + tail, events, "only the named event left the log")
        }
    }

    @Test
    @DisplayName("Naming the log's first message empties the conversation, leaving a promptable session_started")
    fun namingTheFirstMessageEmptiesTheConversation() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val runs = http.twoRuns(id)

            val rewound = http.rewindSession(id, runs.firstRunStart)

            assertTrue(rewound.deletedSessionIds.isEmpty())
            val store = sessionStore(tempDir)
            val events = store.readEvents(id)
            val started = assertIs<AgentEvent.SessionStarted>(events.first(), "no cut may delete the log's opening")
            val tail = assertIs<AgentEvent.ConversationRewound>(events.last())
            assertEquals(started.sequenceId, tail.lastSurvivingSequenceId, "the session_started is the cut point")
            assertEquals(runs.preCutMax + 1, tail.sequenceId)
            assertEquals(listOf(started, tail), events, "the conversation is empty: no run_started is left at all")

            // The read models over a conversation-less log still answer.
            val info = http.sessionInfo(id)
            assertEquals(SessionStatus.IDLE, info.status, "the runtime stays attached — reloaded from the cut log")
            assertEquals(started.title, info.title)
            assertNull(info.lastRun, "no run in the log is no consumption to report, not a failure to report it")

            // Promptable over the emptied conversation: the reload path copes
            // with a log holding none, and the prompt starts a first run again.
            http.prompt(id, "third question")
            http.awaitRunEnd(id)
            val resumed = store.readEvents(id)
            assertIs<AgentEvent.RunFinished>(resumed.last())
            assertEquals(1, resumed.count { it is AgentEvent.RunStarted }, "the fresh run is the log's only one")
            assertEquals(
                tail.sequenceId + 1,
                resumed[resumed.indexOf(tail) + 1].sequenceId,
                "the fresh run continues directly above the rewound tail",
            )
        }
    }

    @Test
    @DisplayName("Naming the log's first event is a 400: the cut may not delete the session_started")
    fun namingTheFirstEventIsRejected() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.twoRuns(id)
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)

            val response = http.rewindResponse(id, before.first().sequenceId)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals(before, store.readEvents(id), "a rejected rewind must not have touched the log")
        }
    }

    @Test
    @DisplayName("Naming an event a cut preserves is a 400: what a cut keeps cannot be what it cuts from")
    fun namingAPreservedEventIsRejected() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "first question")
            http.awaitRunEnd(id)
            // The ordinary state right after renaming an idle session: the
            // rename is the log's last event, so a cut from it would keep the
            // event it named and delete nothing at all.
            http.renameSession(id, "Renamed, then named as a cut point")
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)
            val renamed = assertIs<AgentEvent.SessionRenamed>(before.last())

            val response = http.rewindResponse(id, renamed.sequenceId)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals(before, store.readEvents(id), "a rejected rewind must not have touched the log")
        }
    }

    @Test
    @DisplayName("An absent sequence ID — a deleted one included — is a 400; an unknown session a 404")
    fun absentSequenceIdsAndUnknownSessionsAreRejected() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart, preCutMax) = http.twoRuns(id)

            val beyondTheLog = http.rewindResponse(id, preCutMax + 100)
            assertEquals(HttpStatusCode.BadRequest, beyondTheLog.status)
            assertEquals("invalid_request", beyondTheLog.body<ApiError>().code)

            // A sequence ID an earlier rewind deleted is gone for good — the
            // one it named included.
            http.rewindSession(id, secondRunStart)
            val deletedId = http.rewindResponse(id, secondRunStart)
            assertEquals(HttpStatusCode.BadRequest, deletedId.status)
            assertEquals("invalid_request", deletedId.body<ApiError>().code)

            assertEquals(HttpStatusCode.NotFound, http.rewindResponse("no-such-id", 0).status)
        }
    }

    @Test
    @DisplayName("A missing or malformed body is a 400 invalid_request")
    fun malformedBodiesAreRejected() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            listOf("{}", """{"firstDeletedSequenceId": "not-a-number"}""").forEach { body ->
                val response = http.rawRewindResponse(id, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: '$body'")
                assertEquals("invalid_request", response.body<ApiError>().code, "body: '$body'")
            }
        }
    }

    @Test
    @DisplayName("A rewind while a run is in flight is a 409 that changes nothing")
    fun rewindDuringARunConflicts() {
        withFakeSessionServer(
            tempDir,
            // Long enough in a tool that the rewind's flight over loopback
            // cannot outlast it; the stop below ends the run without needing
            // another scripted turn.
            onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "sleep 5"))),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            awaitBlockedInATool(http, id)

            // A cut point the log holds, so the run in flight is all that
            // stands between this call and a 200 — though the conflict guard
            // runs ahead of validation, so the status alone could not say which
            // of the two refused it.
            val conflict = http.rewindResponse(id, sessionStore(tempDir).readEvents(id).last().sequenceId)

            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("conflict", conflict.body<ApiError>().code)
            val store = sessionStore(tempDir)
            assertTrue(
                store.readEvents(id).none { it is AgentEvent.ConversationRewound },
                "a conflicting rewind must not have touched the log",
            )

            assertEquals(HttpStatusCode.OK, http.stopResponse(id).status)
        }
    }

    // ─── The harness across the rewind ────────────────────────────────

    @Test
    @DisplayName("A harness that no longer loads is a 400 that cuts nothing; the tree tears down to closed")
    fun brokenHarnessAbortsTheRewindBeforeTheCut() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (_, secondRunStart) = http.twoRuns(id)
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)
            tempDir.resolve("harness").resolve("harness.yaml").deleteExisting()

            val response = http.rewindResponse(id, secondRunStart)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_harness", response.body<ApiError>().code)
            assertEquals(before, store.readEvents(id), "an aborted rewind must not have touched the log")
            assertEquals(SessionStatus.CLOSED, http.sessionInfo(id).status)
        }
    }

    @Test
    @DisplayName("A reload failing after the cut degrades to closed; the rewind itself reports success")
    fun failedReloadAfterTheCutDegradesToClosed() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunStart) = http.twoRuns(id)
            // Still a loadable harness, but agent construction rejects the
            // unknown tool — a failure only the rebuild after the cut hits.
            writeHarness(tempDir.resolve("harness"), tools = listOf("no-such-tool"))

            val rewound = http.rewindSession(id, secondRunStart)

            assertEquals(SessionStatus.CLOSED, rewound.session.status)
            assertTrue(rewound.deletedSessionIds.isEmpty())
            val events = sessionStore(tempDir).readEvents(id)
            assertIs<AgentEvent.ConversationRewound>(events.last())
            assertEquals(firstRunEnd, events[events.size - 2].sequenceId, "the cut happened and holds")

            // The next prompt surfaces the rebuild failure itself.
            val next = http.promptResponse(id, "third question")
            assertEquals(HttpStatusCode.BadRequest, next.status)
            assertEquals("invalid_harness", next.body<ApiError>().code)
        }
    }
}

/**
 * The first two runs' boundaries: [secondRunStart] is the event a case names,
 * so the cut deletes exactly the second run and [firstRunEnd] is what survives
 * it; [preCutMax] is the log's greatest sequence ID before the cut, and
 * [firstRunStart] the log's first message — naming it deletes both runs.
 */
private data class TwoRuns(
    val firstRunEnd: Long,
    val secondRunStart: Long,
    val preCutMax: Long,
    val firstRunStart: Long,
)

/** POSTs a rewind whose body is [body] verbatim, for the shapes the request type cannot express. */
private suspend fun HttpClient.rawRewindResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/rewind") {
        setBody(TextContent(body, ContentType.Application.Json))
    }
