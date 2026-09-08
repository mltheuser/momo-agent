package codes.momo.agent.server.cut

import codes.momo.agent.AgentEvent
import codes.momo.agent.server.storage.InvalidRewindPointException
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CutPolicyTest {

    private val log = listOf(
        AgentEvent.SessionStarted(0, 0, sessionId = "s", title = "s", harnessPath = null, workspace = "/work"),
        AgentEvent.RunStarted(1, 1, userMessage = "first"),
        AgentEvent.SessionRenamed(2, 2, title = "renamed"),
        AgentEvent.RunStarted(5, 5, userMessage = "second"),
        AgentEvent.ToolCallStarted(6, 6, callId = "c", toolName = "bash", arguments = JsonObject(emptyMap())),
    )

    @Test
    @DisplayName("The cut point names the first deleted event; the last survivor is the closest earlier event")
    fun lastSurvivorIsTheClosestEarlierEvent() {
        assertEquals(2L, log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = 5))
        assertEquals(0L, log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = 1))
    }

    @Test
    @DisplayName("A cut starts at a user message only: unknown, preserved, opening and in-turn events are refused")
    fun invalidCutPointsAreRefused() {
        listOf<Long>(3, 2, 6, 0).forEach { named ->
            assertFailsWith<InvalidRewindPointException>("cut from $named") {
                log.lastSurvivorOfCutFrom("s", firstDeletedSequenceId = named)
            }
        }
    }
}
