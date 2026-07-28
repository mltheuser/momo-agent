package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlmRule
import codes.momo.agent.RunResult
import codes.momo.agent.assistantResponse
import codes.momo.agent.bashCall
import codes.momo.agent.fromRootAgent
import codes.momo.agent.fromSubagent
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.promptSubagentCall
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.toolCallResponse
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A stop crossing the subagent boundary: which runs it ends, which it leaves
 * to finish, and what the tree looks like afterwards.
 */
class SubagentStopCascadeTest {

    @TempDir
    lateinit var tempDir: Path

    /** The turns of a root run that spawns "helper" and leaves it blocked in a tool no stop can outrun. */
    private fun spawnAndBlockTheHelperRules(): Array<FakeLlmRule> = arrayOf(
        onOpeningTurn(
            toolCallResponse(
                spawnSubagentCall(id = "call-1", name = "helper"),
                promptSubagentCall(id = "call-2", name = "helper", message = PRIME_MESSAGE),
            ),
            saying = SPAWN_PROMPT,
        ).fromRootAgent(),
        // Long enough in a tool that the stop's flight over loopback cannot
        // outlast it, so whatever the stop does is all that happens to the
        // child's run — and short enough that a stop which never cut that run
        // fails in seconds rather than at a wait's ceiling.
        onOpeningTurn(toolCallResponse(bashCall(id = "child-1", command = "sleep 5")), saying = PRIME_MESSAGE)
            .fromSubagent(),
    )

    @Test
    @DisplayName("Stopping a parent blocked on a child cascades: both runs end stopped, both logs stay loadable")
    fun stopOnTheParentCascadesIntoTheChildItIsBlockedOn() {
        withFakeSessionServer(tempDir, *spawnAndBlockTheHelperRules()) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            awaitBlockedInATool(http, childId)

            assertEquals(HttpStatusCode.OK, http.stopResponse(rootId).status)

            val store = SessionStore(tempDir.resolve("data"))
            listOf(rootId, childId).forEach { id ->
                assertEquals(
                    RunResult.Status.STOPPED,
                    assertIs<AgentEvent.RunFinished>(http.streamEvents(id).last().event).status,
                    "the stop must end $id's run as stopped",
                )
                // The cut leaves a log that still loads: readable, gapless,
                // and terminated by the outcome the stop recorded.
                val events = store.readEvents(id)
                assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId }, "$id's log has gaps")
                assertIs<AgentEvent.RunFinished>(events.last())
            }
            // Idle, not closed: the tree stayed attached over the same
            // collaborators, and its members are promptable again.
            http.awaitRunEnd(rootId)
            assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status)
            assertEquals(SessionStatus.IDLE, http.sessionInfo(childId).status)
        }
    }

    @Test
    @DisplayName("Stopping a parent-driven child ends that child's run only; the parent reads it and finishes its own")
    fun stopOnAChildEndsItsRunOnly() {
        withFakeSessionServer(
            tempDir,
            *spawnAndBlockTheHelperRules(),
            onToolResults(
                assistantResponse(finishReason = "stop", text = "the helper was stopped"),
                saying = "run ended as STOPPED",
            ).fromRootAgent(),
        ) { http ->
            val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
            http.prompt(rootId, SPAWN_PROMPT)
            val childId = spawnedChildId(http, rootId)
            awaitBlockedInATool(http, childId)

            val stopped = http.stopResponse(childId)

            // Idle already in the stop's own answer, and still attached.
            assertEquals(HttpStatusCode.OK, stopped.status)
            assertEquals(SessionStatus.IDLE, stopped.body<SessionInfo>().status)
            assertEquals(
                RunResult.Status.STOPPED,
                assertIs<AgentEvent.RunFinished>(http.streamEvents(childId).last().event).status,
            )

            // Only the child's run was cut: the parent took the stopped child
            // as an error result and finished a run of its own.
            http.awaitRunEnd(rootId)
            val parentRun = http.streamEvents(rootId).map { it.event }
            assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(parentRun.last()).status)
            assertContains(
                parentRun.filterIsInstance<AgentEvent.ToolCallFinished>().single { it.callId == "call-2" }.resultText,
                "'helper' run ended as STOPPED",
            )
            assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status)
        }
    }
}
