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
import kotlin.test.assertTrue

/**
 * The rewind endpoint on a single session: the cut and its announcement,
 * the streams surviving the shrunken file, the no-op, the error surface,
 * and the truncated log as the resume and restart source of truth.
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

    /** Runs the "first" and "second" prompts to completion and returns each run's last sequence ID. */
    private suspend fun HttpClient.twoRuns(id: String): Pair<Long, Long> {
        prompt(id, "first question")
        val firstRunEnd = streamEvents(id).last().id
        prompt(id, "second question")
        val secondRunEnd = streamEvents(id, afterSequenceId = firstRunEnd).last().id
        return firstRunEnd to secondRunEnd
    }

    // ─── The cut ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Rewinding an attached session cuts the log, announces it, and leaves the session promptable")
    fun rewindCutsTheLogAndStaysPromptable() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunEnd) = http.twoRuns(id)

            val rewound = http.rewindSession(id, firstRunEnd)

            assertEquals(
                SessionStatus.IDLE,
                rewound.session.status,
                "the runtime stays attached — reloaded, not closed",
            )
            assertTrue(rewound.deletedSessionIds.isEmpty())
            val events = sessionStore(tempDir).readEvents(id)
            val tail = assertIs<AgentEvent.ConversationRewound>(events.last())
            assertEquals(firstRunEnd, tail.lastSurvivingSequenceId)
            assertEquals(secondRunEnd + 1, tail.sequenceId, "the tail is numbered above the log's pre-cut maximum")
            assertEquals(firstRunEnd, events[events.size - 2].sequenceId, "the named event is the last survivor")
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
    @DisplayName("Rewinding a closed session stays closed; the next prompt resumes from the truncated log")
    fun rewindingAClosedSessionStaysClosed() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, _) = http.twoRuns(id)
            http.closeSession(id)

            val rewound = http.rewindSession(id, firstRunEnd)

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
            val (firstRunEnd, _) = http.twoRuns(id)
            http.rewindSession(id, firstRunEnd)
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
            val (firstRunEnd, secondRunEnd) = http.twoRuns(id)

            coroutineScope {
                // Parked past everything stored: the only event it can ever
                // receive is one the rewind appends past the shrunken file.
                val watcher = async {
                    http.streamEvents(id, afterSequenceId = secondRunEnd) { it is AgentEvent.ConversationRewound }
                }
                http.rewindSession(id, firstRunEnd)

                val received = watcher.await()
                val tail = assertIs<AgentEvent.ConversationRewound>(received.single().event)
                assertEquals(firstRunEnd, tail.lastSurvivingSequenceId)
                assertEquals(secondRunEnd + 1, received.single().id)
            }
        }
    }

    @Test
    @DisplayName("A Last-Event-ID reconnect from inside the deleted range converges on the conversation_rewound")
    fun reconnectFromInsideTheDeletedRangeConverges() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunEnd) = http.twoRuns(id)
            http.rewindSession(id, firstRunEnd)

            val insideDeletedRange = (firstRunEnd + 1).also { assertTrue(it < secondRunEnd) }
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

    // ─── The no-op and the error surface ──────────────────────────────

    @Test
    @DisplayName("Naming the log's last event is a no-op success: nothing deleted, nothing appended")
    fun namingTheLastEventIsANoOp() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "first question")
            val lastId = http.streamEvents(id).last().id
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)

            val rewound = http.rewindSession(id, lastId)

            assertTrue(rewound.deletedSessionIds.isEmpty())
            assertEquals(before, store.readEvents(id), "a no-op rewind leaves the log byte-identical")
        }
    }

    @Test
    @DisplayName("An absent sequence ID — a deleted one included — is a 400; an unknown session a 404")
    fun absentSequenceIdsAndUnknownSessionsAreRejected() {
        withFakeSessionServer(tempDir, *threeRunRules()) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            val (firstRunEnd, secondRunEnd) = http.twoRuns(id)

            val beyondTheLog = http.rewindResponse(id, secondRunEnd + 100)
            assertEquals(HttpStatusCode.BadRequest, beyondTheLog.status)
            assertEquals("invalid_request", beyondTheLog.body<ApiError>().code)

            // A sequence ID an earlier rewind deleted is gone for good.
            http.rewindSession(id, firstRunEnd)
            val deletedId = http.rewindResponse(id, firstRunEnd + 1)
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

            listOf("{}", """{"sequenceId": "not-a-number"}""").forEach { body ->
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

            val conflict = http.rewindResponse(id, 0)

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
            val (firstRunEnd, _) = http.twoRuns(id)
            val store = sessionStore(tempDir)
            val before = store.readEvents(id)
            tempDir.resolve("harness").resolve("harness.yaml").deleteExisting()

            val response = http.rewindResponse(id, firstRunEnd)

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
            val (firstRunEnd, _) = http.twoRuns(id)
            // Still a loadable harness, but agent construction rejects the
            // unknown tool — a failure only the rebuild after the cut hits.
            writeHarness(tempDir.resolve("harness"), tools = listOf("no-such-tool"))

            val rewound = http.rewindSession(id, firstRunEnd)

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

/** POSTs a rewind whose body is [body] verbatim, for the shapes the request type cannot express. */
private suspend fun HttpClient.rawRewindResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/rewind") {
        setBody(TextContent(body, ContentType.Application.Json))
    }
