package codes.momo.agent

import ai.router.sdk.dsl.chatRequest
import ai.router.sdk.models.decode
import ai.router.sdk.schema.Description
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The drift detector between momo-agent and ai-router: one tool-calling
 * round trip built from, and decoded back into, the SDK's own types. Every
 * field the agent loop reads off a response is asserted here, so a change
 * to ai-router's wire contract fails a test instead of quietly changing
 * what a fake elsewhere is believed to stand for.
 */
class AiRouterContractLiveTest {

    @Serializable
    data class ReadingArgs(
        @Description("Name of the station the reading came from.")
        val station: String,
        @Description("The measured temperature in degrees Celsius.")
        val celsius: Int,
    )

    @Test
    @DisplayName("SDK contract: a tool-calling round trip decodes into the SDK's own types")
    fun toolCallingRoundTrip() = runBlocking {
        liveAiRouterClient().use { client ->
            val request = chatRequest(liveChatModel) {
                messages {
                    // A prompt has to invite the completion the assertions read.
                    // Forbidding prose here made the model emit chat-template
                    // control tokens and, once, wedged the call for minutes.
                    system {
                        text(
                            "You keep a station's temperature log. Use your record_reading tool whenever a " +
                                "reading must be stored, then confirm briefly what you recorded.",
                        )
                    }
                    user { text("Station $STATION measured $CELSIUS degrees Celsius. Record that reading.") }
                }
                tools {
                    tool<ReadingArgs>("record_reading", "Records one temperature reading from a station.")
                }
            }

            val response = client.chat(request)

            assertTrue(response.model.isNotBlank(), "the response must name the model that served it")
            assertTrue(response.finishReason.isNotBlank(), "the response must carry a finish reason")
            assertEquals("assistant", response.message.role)
            assertTrue(response.usage.promptTokens > 0, "usage must count prompt tokens")
            assertTrue(response.usage.completionTokens > 0, "usage must count completion tokens")
            // Not their sum: the total is whatever the router reports, and
            // reasoning or cache-read tokens are part of it on some backends.
            assertTrue(response.usage.totalTokens > 0, "usage must report a total")

            val call = assertNotNull(
                response.message.toolCalls?.firstOrNull(),
                "expected a tool call (finish_reason=${response.finishReason}, text='${response.textContent}')",
            )
            assertEquals("record_reading", call.function.name)
            assertTrue(call.id.isNotBlank(), "a tool call must be addressable by id")

            // The decode is the contract: the arguments the router returns
            // must still fit the class the request's schema was generated from.
            val arguments = call.decode<ReadingArgs>()
            assertEquals(CELSIUS, arguments.celsius)
            assertTrue(
                arguments.station.contains(STATION, ignoreCase = true),
                "expected the planted station name in the arguments: $arguments",
            )

            // The return leg, in exactly the message shape the agent loop
            // sends: a tool result the router accepts and answers.
            val answer = client.chat(
                request.copy(
                    messages = request.messages +
                        response.message +
                        toolResultMessage(call.id, "Stored reading 1 for ${arguments.station}."),
                ),
            )
            assertEquals("assistant", answer.message.role)
            assertTrue(answer.finishReason.isNotBlank(), "the follow-up must carry a finish reason")
            assertTrue(answer.usage.totalTokens > 0, "the follow-up must be accounted for")
        }
    }
}

/** Planted values a hallucinated tool call cannot reproduce. */
private const val STATION: String = "Yaffle"

private const val CELSIUS: Int = 21
