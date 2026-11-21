package codes.momo.agent.tool

import ai.router.sdk.chat.ToolCall
import ai.router.sdk.chat.ToolCallFunction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CommandTimeoutTest {

    private fun timeoutOfBashCall(written: String): Duration {
        val arguments = buildJsonObject {
            put("command", "true")
            put("timeout", written)
        }
        return ToolCall("call", ToolCallFunction("bash", arguments)).decode(BashArgs.serializer()).timeout
    }

    @Test
    @DisplayName("A bash call's timeout is a number with a unit between one second and a day; anything else is refused")
    fun onlyDurationsInRangeAreAccepted() {
        val accepted = mapOf(
            "1s" to 1.seconds,
            "90s" to 90.seconds,
            "10m" to 10.minutes,
            "1h 30m" to 90.minutes,
            "24h" to 24.hours,
        )
        accepted.forEach { (written, duration) -> assertEquals(duration, timeoutOfBashCall(written), written) }

        listOf("600", "soon", "", "0s", "500ms", "-5m", "25h", "Infinity").forEach { written ->
            val refusal = assertFailsWith<SerializationException>("'$written' must be refused") {
                timeoutOfBashCall(written)
            }
            val message = assertNotNull(refusal.message)
            assertContains(message, "at least 1s and at most 24h", message = "'$written': the range is named")
            assertContains(message, "\"10m\"", message = "'$written': the refusal shows how to write a timeout")
        }
    }
}
