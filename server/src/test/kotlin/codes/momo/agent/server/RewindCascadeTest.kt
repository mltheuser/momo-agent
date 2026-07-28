package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.assistantResponse
import codes.momo.agent.fromRootAgent
import codes.momo.agent.fromSubagent
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.promptSubagentCall
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.toolCallResponse
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A rewind crossing the subagent boundary: which children the deleted range
 * deletes, which it cuts back, and which it leaves alone — plus a rewind
 * aimed at a child directly, and what a child's open stream sees when the
 * cascade deletes or cuts it.
 */
class RewindCascadeTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── The cascade over the stored logs ─────────────────────────────

    @Test
    @DisplayName("Rewinding past a spawn deletes that child's subtree; a sibling spawned earlier keeps its log")
    fun rewindPastASpawnDeletesTheChildAndFreesItsName() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-3", name = "other"),
                    promptSubagentCall(id = "call-4", name = "other", message = OTHER_PRIME),
                ),
                saying = SECOND_SPAWN,
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = OTHER_ANSWER), saying = OTHER_PRIME)
                .fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "second spawn done"), saying = OTHER_ANSWER)
                .fromRootAgent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val helperId = spawnedChildId(http, rootId)
            val firstRunEnd = http.streamEvents(rootId).last().id
            http.prompt(rootId, SECOND_SPAWN)
            http.awaitRunEnd(rootId)
            val otherId = sessionStore(tempDir).readEvents(rootId).filterIsInstance<AgentEvent.SubagentSpawned>()
                .single { it.name == "other" }.sessionId
            val helperLog = sessionStore(tempDir).readEvents(helperId)

            val rewound = http.rewindSession(rootId, firstRunEnd)

            // The deleted spawn takes its child's subtree, named in the
            // response so clients can close its panels; the earlier sibling
            // and its log are untouched.
            assertEquals(listOf(otherId), rewound.deletedSessionIds)
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$otherId").status)
            assertEquals(
                helperLog,
                sessionStore(tempDir).readEvents(helperId),
                "the untouched descendant keeps its log",
            )
            assertEquals(SessionStatus.IDLE, http.sessionInfo(helperId).status)

            // The name is free to spawn anew: the same scripted run produces
            // a fresh session under the freed name.
            http.prompt(rootId, SECOND_SPAWN)
            http.awaitRunEnd(rootId)
            val respawned = sessionStore(tempDir).readEvents(rootId).filterIsInstance<AgentEvent.SubagentSpawned>()
                .single { it.name == "other" }
            assertNotEquals(otherId, respawned.sessionId)
        }
    }

    @Test
    @DisplayName("Rewinding past a prompt_subagent cuts the child before the driven run, keeping earlier human runs")
    fun rewindPastAPromptCutsTheDrivenChild() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "vanilla it is"), saying = HUMAN_FOLLOW_UP)
                .fromSubagent(),
            onOpeningTurn(
                toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = SECOND_TASK)),
                saying = DRIVE_AGAIN,
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = SECOND_ANSWER), saying = SECOND_TASK)
                .fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "drove again"), saying = SECOND_ANSWER)
                .fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "noted"), saying = LATER_HUMAN_PROMPT)
                .fromSubagent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "still here"), saying = AFTER_REWIND_PROMPT)
                .fromSubagent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            // Root run 1 spawns and primes the helper (child run 1)...
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            val firstRunEnd = http.streamEvents(rootId).last().id
            // ...a human drives child run 2 directly...
            http.prompt(childId, HUMAN_FOLLOW_UP)
            http.awaitRunEnd(childId)
            val humanRunEnd = sessionStore(tempDir).readEvents(childId).last().sequenceId
            // ...root run 2 drives child run 3, and a human drives child run 4.
            http.prompt(rootId, DRIVE_AGAIN)
            http.awaitRunEnd(rootId)
            http.prompt(childId, LATER_HUMAN_PROMPT)
            http.awaitRunEnd(childId)
            assertEquals(4, sessionStore(tempDir).readEvents(childId).count { it is AgentEvent.RunStarted })

            val rewound = http.rewindSession(rootId, firstRunEnd)

            // Nothing is deleted outright — the helper's spawn survives; its
            // log is cut strictly before the run the deleted call drove,
            // taking the later human-driven run with it but keeping the
            // earlier one.
            assertTrue(rewound.deletedSessionIds.isEmpty())
            val childEvents = sessionStore(tempDir).readEvents(childId)
            val tail = assertIs<AgentEvent.ConversationRewound>(childEvents.last())
            assertEquals(humanRunEnd, tail.lastSurvivingSequenceId)
            assertEquals(2, childEvents.count { it is AgentEvent.RunStarted })
            assertEquals(humanRunEnd, childEvents[childEvents.size - 2].sequenceId)
            val rootEvents = sessionStore(tempDir).readEvents(rootId)
            assertEquals(1, rootEvents.count { it is AgentEvent.RunStarted })
            assertIs<AgentEvent.ConversationRewound>(rootEvents.last(), "every touched log gets its own tail")

            // The child is promptable again, resuming above its own gap.
            http.prompt(childId, AFTER_REWIND_PROMPT)
            http.awaitRunEnd(childId)
            val resumed = sessionStore(tempDir).readEvents(childId)
            assertIs<AgentEvent.RunFinished>(resumed.last())
            assertEquals(tail.sequenceId + 1, resumed[resumed.indexOf(tail) + 1].sequenceId)
        }
    }

    // ─── A child as the rewind's target ───────────────────────────────

    @Test
    @DisplayName("Rewinding a child directly cuts the child's own log and leaves the root's untouched")
    fun rewindTargetingAChildCutsOnlyTheChild() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "vanilla it is"), saying = HUMAN_FOLLOW_UP)
                .fromSubagent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "still here"), saying = AFTER_REWIND_PROMPT)
                .fromSubagent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            // Root run 1 spawns and primes the child (child run 1)...
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)
            val primedRunEnd = sessionStore(tempDir).readEvents(childId).last().sequenceId
            // ...then a human drives child run 2 directly.
            http.prompt(childId, HUMAN_FOLLOW_UP)
            http.awaitRunEnd(childId)
            val childPreCutMax = sessionStore(tempDir).readEvents(childId).last().sequenceId
            val rootLog = sessionStore(tempDir).readEvents(rootId)

            val rewound = http.rewindSession(childId, primedRunEnd)

            // The cut lands on the child alone — the guard and the reload
            // ran on the root, whose own log the rewind never touches.
            assertEquals(SessionStatus.IDLE, rewound.session.status)
            assertTrue(rewound.deletedSessionIds.isEmpty())
            val childEvents = sessionStore(tempDir).readEvents(childId)
            val tail = assertIs<AgentEvent.ConversationRewound>(childEvents.last())
            assertEquals(primedRunEnd, tail.lastSurvivingSequenceId)
            assertEquals(childPreCutMax + 1, tail.sequenceId)
            assertEquals(primedRunEnd, childEvents[childEvents.size - 2].sequenceId)
            assertEquals(1, childEvents.count { it is AgentEvent.RunStarted })
            assertEquals(rootLog, sessionStore(tempDir).readEvents(rootId), "the root's log is untouched")

            // The reloaded tree is usable at once: the child promptable
            // again, resuming above its own gap.
            http.prompt(childId, AFTER_REWIND_PROMPT)
            http.awaitRunEnd(childId)
            val resumed = sessionStore(tempDir).readEvents(childId)
            assertIs<AgentEvent.RunFinished>(resumed.last())
            assertEquals(tail.sequenceId + 1, resumed[resumed.indexOf(tail) + 1].sequenceId)
        }
    }

    // ─── A child's open streams under the cascade ─────────────────────

    @Test
    @DisplayName("A rewind deleting a child ends the child's open event stream instead of leaving it parked")
    fun cascadeDeletionEndsTheChildsOpenStream() {
        withSpawnedChild(tempDir) { http, rootId, childId ->
            coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                // Collects until the server itself ends the stream: nothing
                // can match, so only the deletion's completion returns it.
                val watcher = async {
                    http.streamEvents(childId, onSubscribed = { subscribed.complete(Unit) }, until = { false })
                }
                subscribed.await()

                val rewound = http.rewindSession(rootId, 0)

                assertEquals(listOf(childId), rewound.deletedSessionIds)
                val seen = watcher.await()
                assertTrue(seen.none { it.event is AgentEvent.ConversationRewound }, "deleted outright, never cut")
                assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$childId").status)
            }
        }
    }

    @Test
    @DisplayName("A rewind cutting a child delivers its conversation_rewound to a subscriber parked on the open stream")
    fun cascadeCutReachesTheChildsParkedStream() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            onOpeningTurn(
                toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = SECOND_TASK)),
                saying = DRIVE_AGAIN,
            ).fromRootAgent(),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = SECOND_ANSWER), saying = SECOND_TASK)
                .fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "drove again"), saying = SECOND_ANSWER)
                .fromRootAgent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            val firstRunEnd = http.streamEvents(rootId).last().id
            val childRunOneEnd = sessionStore(tempDir).readEvents(childId).last().sequenceId
            // Root run 2 drives child run 2 — the run the rewind will cut.
            http.prompt(rootId, DRIVE_AGAIN)
            http.awaitRunEnd(rootId)
            val childPreCutMax = sessionStore(tempDir).readEvents(childId).last().sequenceId

            coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                // Parked past everything the child's log stores, on the
                // inode the cascade's cut will replace...
                val watcher = async {
                    http.streamEvents(
                        childId,
                        afterSequenceId = childPreCutMax,
                        onSubscribed = { subscribed.complete(Unit) },
                    ) { it is AgentEvent.ConversationRewound }
                }
                subscribed.await()

                http.rewindSession(rootId, firstRunEnd)

                // ...so the one event it can receive is the child's own
                // rewound tail, arriving without a reconnect.
                val received = watcher.await()
                val tail = assertIs<AgentEvent.ConversationRewound>(received.single().event)
                assertEquals(childRunOneEnd, tail.lastSurvivingSequenceId)
                assertEquals(childPreCutMax + 1, received.single().id)
            }
        }
    }
}

/** The prompt of the root's second spawning run, and the constants that script it. */
private const val SECOND_SPAWN: String = "spawn the other helper"
private const val OTHER_PRIME: String = "get ready too"
private const val OTHER_ANSWER: String = "the other helper is ready"

/** The prompts around the driven child run the rewind deletes. */
private const val HUMAN_FOLLOW_UP: String = "make it vanilla"
private const val DRIVE_AGAIN: String = "have the helper do more"
private const val SECOND_TASK: String = "do the second task"
private const val SECOND_ANSWER: String = "second task done"
private const val LATER_HUMAN_PROMPT: String = "one more thing"
private const val AFTER_REWIND_PROMPT: String = "are you still there"
