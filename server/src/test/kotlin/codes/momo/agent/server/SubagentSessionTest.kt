package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.assertTwoCleanRuns
import codes.momo.agent.assistantResponse
import codes.momo.agent.bashCall
import codes.momo.agent.fromRootAgent
import codes.momo.agent.fromSubagent
import codes.momo.agent.harness.writeHarness
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.promptSubagentCall
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.toolCallResponse
import codes.momo.agent.underInstructions
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A spawned child is a session in its own right: addressable, promptable by a
 * human, renamable and favoritable through its own ID, and deletable on its
 * own.
 */
class SubagentSessionTest {

    @TempDir
    lateinit var tempDir: Path

    /** A stream end condition matching the [count]th run end, for a session that runs more than once. */
    private fun runsFinished(count: Int): (AgentEvent) -> Boolean {
        var seen = 0
        return { event -> event is AgentEvent.RunFinished && ++seen == count }
    }

    // ─── Children are sessions ────────────────────────────────────────

    @Test
    @DisplayName("A human follow-up prompt reaches an idle child; prompting the child while it runs is a 409")
    fun humanPromptsAnIdleChildAndConflictsWithARunningOne() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            // A second in a tool outlasts the conflicting prompt's flight past
            // the barrier below, and still lets the run finish for the
            // two-clean-runs assertion this case ends on.
            onOpeningTurn(toolCallResponse(bashCall(id = "child-1", command = "sleep 1")), saying = FOLLOW_UP)
                .fromSubagent(),
            onToolResults(assistantResponse(finishReason = "stop", text = "the answer is vanilla")).fromSubagent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)

            // The idle child takes the human's message into its own conversation.
            assertEquals(SessionStatus.RUNNING, http.prompt(childId, FOLLOW_UP).status)
            assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status, "no parent is driving this run")
            awaitBlockedInATool(http, childId, run = 2)

            val conflict = http.promptResponse(childId, "impatient follow-up")

            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("conflict", conflict.body<ApiError>().code)

            http.awaitRunEnd(childId)
            assertTwoCleanRuns(http.streamEvents(childId, until = runsFinished(2)).map { it.event }, FOLLOW_UP)
        }
    }

    @Test
    @DisplayName("A typed child's info names the referenced harness folder it runs, resolved hop by hop")
    fun typedChildrenReportTheirOwnHarness() {
        val leaf = writeHarness(tempDir.resolve("leaf"), instructions = LEAF_INSTRUCTIONS)
        val mid = writeHarness(
            tempDir.resolve("mid"),
            subagents = mapOf("leaf" to "../leaf"),
            instructions = MID_INSTRUCTIONS,
        )
        val rootFolder = writeHarness(
            tempDir.resolve("harness"),
            subagents = mapOf("mid" to "../mid"),
            instructions = ROOT_INSTRUCTIONS,
        )

        // Each level runs a harness of its own, so its instructions are what
        // says which agent a turn came from.
        withFakeSessionServer(
            tempDir,
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-1", name = "helper", type = "mid"),
                    promptSubagentCall(id = "call-2", name = "helper", message = "dig deeper"),
                ),
            ).underInstructions(ROOT_INSTRUCTIONS),
            onOpeningTurn(
                toolCallResponse(
                    spawnSubagentCall(id = "call-3", name = "deep", type = "leaf"),
                    promptSubagentCall(id = "call-4", name = "deep", message = "dig"),
                ),
            ).underInstructions(MID_INSTRUCTIONS),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "bedrock"))
                .underInstructions(LEAF_INSTRUCTIONS),
            onToolResults(assistantResponse(finishReason = "stop", text = "deep says bedrock"))
                .underInstructions(MID_INSTRUCTIONS),
            onToolResults(assistantResponse(finishReason = "stop", text = "done"))
                .underInstructions(ROOT_INSTRUCTIONS),
        ) { http ->
            val root = http.createSession(rootFolder.toString(), localWorkspace(tempDir))
            http.prompt(root.id, "go")
            val childId = spawnedChildId(http, root.id)
            val grandId = spawnedChildId(http, childId)
            http.awaitRunEnd(root.id)

            val child = http.sessionInfo(childId)
            val grand = http.sessionInfo(grandId)
            assertNotEquals(root.harnessPath, child.harnessPath, "a typed child runs a folder of its own")
            assertEquals(mid.toRealPath().toString(), child.harnessPath)
            assertEquals(leaf.toRealPath().toString(), grand.harnessPath)
            // The parent chain over two hops, and the root's one environment
            // at every level of it.
            assertEquals(root.id, child.parent)
            assertEquals(childId, grand.parent)
            assertEquals(listOf(root.environment, root.environment), listOf(child.environment, grand.environment))
            assertEquals(2, assertIs<AgentEvent.SessionStarted>(http.streamEvents(grandId).first().event).depth)
        }
    }

    // ─── Rename & favorite through a child ────────────────────────────

    @Test
    @DisplayName("Favoriting through a child's ID marks the tree-wide flag on the root, appending no events")
    fun favoriteThroughAChildMarksTheRoot() {
        withSpawnedChild(tempDir) { http, rootId, childId ->
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
        withSpawnedChild(tempDir) { http, rootId, childId ->
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

    // ─── Deleting a child ─────────────────────────────────────────────

    @Test
    @DisplayName("A directly deleted child is unknown to a later prompt_subagent and its name is free to reuse")
    fun deletedChildYieldsUnknownName() {
        withFakeSessionServer(
            tempDir,
            *spawnHelperRules(),
            onOpeningTurn(
                toolCallResponse(promptSubagentCall(id = "call-3", name = "helper", message = "still there?")),
                saying = CHECK_PROMPT,
            ).fromRootAgent(),
            onToolResults(
                toolCallResponse(spawnSubagentCall(id = "call-4", name = "helper")),
                saying = "no subagent named 'helper'",
            ).fromRootAgent(),
            onToolResults(
                assistantResponse(finishReason = "stop", text = "respawned"),
                saying = "spawned subagent 'helper'",
            ).fromRootAgent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            http.awaitRunEnd(rootId)
            val firstRun = http.streamEvents(rootId)

            // Deleting the child directly: its artifacts go, the tree closes, the root survives.
            http.delete("/v1/sessions/$childId")
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/$childId").status)
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$rootId").body<SessionInfo>().status)

            // The resumed parent finds the name unknown — visible in its log — then free to spawn again.
            http.prompt(rootId, CHECK_PROMPT)
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

/** What a human prompts an already-idle child with — the child's second run, in its own conversation. */
private const val FOLLOW_UP: String = "make it vanilla"

/** The prompt the resumed run opens with, once the child has been deleted from under it. */
private const val CHECK_PROMPT: String = "check on the helper"

/** One per level of the typed tree: which harness a turn's system prompt carries is which agent sent it. */
private const val ROOT_INSTRUCTIONS: String = "Root harness instructions."
private const val MID_INSTRUCTIONS: String = "Mid harness instructions."
private const val LEAF_INSTRUCTIONS: String = "Leaf harness instructions."
