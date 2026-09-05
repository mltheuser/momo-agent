package codes.momo.agent.server

import ai.router.sdk.models.ChatUsage
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class RewindPlanTest {

    private fun started(seq: Long, at: Long, id: String) =
        AgentEvent.SessionStarted(seq, at, sessionId = id, title = id)

    private fun run(seq: Long, at: Long) = AgentEvent.RunStarted(seq, at, userMessage = "go")

    private fun finished(seq: Long, at: Long) = AgentEvent.RunFinished(
        sequenceId = seq,
        timestampMillis = at,
        status = RunResult.Status.COMPLETED,
        finalMessage = "done",
        usage = ChatUsage(0, 0, 0, 0, 0),
        turnsUsed = 1,
        elapsed = Duration.ZERO,
    )

    private fun spawned(seq: Long, at: Long, name: String, sessionId: String) =
        AgentEvent.SubagentSpawned(seq, at, name, sessionId, type = "self", modelId = null)

    private fun promptCall(seq: Long, at: Long, callId: String, name: String) = AgentEvent.ToolCallStarted(
        sequenceId = seq,
        timestampMillis = at,
        callId = callId,
        toolName = "prompt_subagent",
        arguments = buildJsonObject {
            put("name", name)
            put("message", "work")
        },
    )

    private fun callFinished(seq: Long, at: Long, callId: String) = AgentEvent.ToolCallFinished(
        sequenceId = seq,
        timestampMillis = at,
        callId = callId,
        resultText = "ok",
        outcome = AgentEvent.ToolCallFinished.Outcome.SUCCESS,
        duration = Duration.ZERO,
        truncated = false,
    )

    private fun plan(cut: Long, vararg logs: Pair<String, List<AgentEvent>>): RewindPlan =
        rewindPlan("root", cut, logs.toMap()::get)

    @Test
    @DisplayName("A deleted prompt call cuts the child strictly before the run it drove, by the timestamp window")
    fun deletedPromptCallCutsTheDrivenChild() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            run(1, at = 10),
            spawned(2, at = 11, name = "helper", sessionId = "child"),
            promptCall(3, at = 12, callId = "call-1", name = "helper"),
            callFinished(4, at = 20, callId = "call-1"),
            finished(5, at = 21),
            run(6, at = 40),
            promptCall(7, at = 41, callId = "call-2", name = "helper"),
            callFinished(8, at = 60, callId = "call-2"),
            finished(9, at = 61),
        )
        val child = listOf(
            started(0, at = 11, id = "child"),
            run(1, at = 13),
            finished(2, at = 19),
            run(3, at = 30),
            finished(4, at = 35),
            run(5, at = 42),
            finished(6, at = 59),
            run(7, at = 70),
            finished(8, at = 75),
        )

        val plan = plan(5, "root" to root, "child" to child)

        assertEquals(listOf("root" to 5L, "child" to 4L), plan.cuts)
        assertTrue(plan.deletedSubtreeRoots.isEmpty())
    }

    @Test
    @DisplayName("A deleted call with no recorded finish takes the first child run at or after its start")
    fun callWithoutFinishTakesTheFirstRunAfterItsStart() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            spawned(1, at = 1, name = "helper", sessionId = "child"),
            finished(2, at = 5),
            run(3, at = 10),
            promptCall(4, at = 12, callId = "call-1", name = "helper"),

        )
        val child = listOf(
            started(0, at = 1, id = "child"),
            run(1, at = 13),
            finished(2, at = 20),
        )

        val plan = plan(2, "root" to root, "child" to child)

        assertEquals(listOf("root" to 2L, "child" to 0L), plan.cuts)
    }

    @Test
    @DisplayName("A deleted call that started no run — nothing in its window — cuts nothing")
    fun callThatStartedNoRunCutsNothing() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            spawned(1, at = 1, name = "helper", sessionId = "child"),
            finished(2, at = 5),
            run(3, at = 10),

            promptCall(4, at = 12, callId = "call-1", name = "helper"),
            callFinished(5, at = 14, callId = "call-1"),
            finished(6, at = 20),
        )
        val child = listOf(
            started(0, at = 1, id = "child"),
            run(1, at = 30),
            finished(2, at = 40),
        )

        val plan = plan(2, "root" to root, "child" to child)

        assertEquals(listOf("root" to 2L), plan.cuts)
        assertTrue(plan.deletedSubtreeRoots.isEmpty())
    }

    @Test
    @DisplayName("A name rebound by a spawn inside the deleted range binds to the deleted child: no cut")
    fun nameReboundByALaterSpawnIsNotCut() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            spawned(1, at = 1, name = "helper", sessionId = "old-child"),
            finished(2, at = 5),
            run(3, at = 10),
            spawned(4, at = 11, name = "helper", sessionId = "new-child"),
            promptCall(5, at = 12, callId = "call-1", name = "helper"),
            callFinished(6, at = 20, callId = "call-1"),
            finished(7, at = 21),
        )
        val newChild = listOf(
            started(0, at = 11, id = "new-child"),
            run(1, at = 13),
            finished(2, at = 19),
        )

        val plan = plan(2, "root" to root, "new-child" to newChild)

        assertEquals(listOf("new-child"), plan.deletedSubtreeRoots)
        assertEquals(listOf("root" to 2L), plan.cuts)
    }

    @Test
    @DisplayName("The cascade recurses: a cut child's own deleted range deletes and cuts its children")
    fun cascadeRecursesIntoACutChild() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            spawned(1, at = 1, name = "mid", sessionId = "mid"),
            finished(2, at = 5),
            run(3, at = 10),
            promptCall(4, at = 11, callId = "call-1", name = "mid"),
            callFinished(5, at = 50, callId = "call-1"),
            finished(6, at = 51),
        )
        val mid = listOf(
            started(0, at = 1, id = "mid"),
            run(1, at = 12),
            spawned(2, at = 13, name = "leaf", sessionId = "doomed-leaf"),
            promptCall(3, at = 14, callId = "call-2", name = "kept-leaf"),
            finished(4, at = 49),
        )

        val plan = plan(2, "root" to root, "mid" to mid)

        assertEquals(listOf("root" to 2L, "mid" to 0L), plan.cuts)
        assertEquals(listOf("doomed-leaf"), plan.deletedSubtreeRoots)
    }

    @Test
    @DisplayName("Cuts iterate ancestors before descendants, so applying them reversed is leaves-first")
    fun cutsAreOrderedAncestorsFirst() {
        val root = listOf(
            started(0, at = 0, id = "root"),
            spawned(1, at = 1, name = "mid", sessionId = "mid"),
            finished(2, at = 5),
            run(3, at = 10),
            promptCall(4, at = 11, callId = "call-1", name = "mid"),
            callFinished(5, at = 90, callId = "call-1"),
            finished(6, at = 91),
        )
        val mid = listOf(
            started(0, at = 1, id = "mid"),
            spawned(1, at = 2, name = "leaf", sessionId = "leaf"),
            run(2, at = 12),
            promptCall(3, at = 13, callId = "call-2", name = "leaf"),
            callFinished(4, at = 30, callId = "call-2"),
            finished(5, at = 89),
        )
        val leaf = listOf(
            started(0, at = 2, id = "leaf"),
            run(1, at = 14),
            finished(2, at = 29),
        )

        val plan = plan(2, "root" to root, "mid" to mid, "leaf" to leaf)

        assertEquals(listOf("root" to 2L, "mid" to 1L, "leaf" to 0L), plan.cuts)
    }
}
