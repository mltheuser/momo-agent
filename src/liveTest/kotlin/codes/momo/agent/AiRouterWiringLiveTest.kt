package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.dsl.chatRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Verifies the wiring to a running local ai-router: one chat round-trip
 * through the SDK's default client must yield non-empty text content.
 */
class AiRouterWiringLiveTest {

    @Test
    @DisplayName("Wiring: send one chat request through the local ai-router and read the response")
    fun chatRoundTrip() = runBlocking {
        AiRouterClient(liveBaseUrl).use { client ->
            val response = client.chat(
                chatRequest(liveChatModel) {
                    messages {
                        system { text("You are a helpful assistant.") }
                        user { text("What is the capital of France?") }
                    }
                }
            )

            assertTrue(
                response.textContent.isNotBlank(),
                "expected non-empty text content (finish_reason=${response.finishReason})",
            )
        }
    }
}
