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
 *
 * Configuration arrives as system properties set by the `liveTest` Gradle
 * task (see build.gradle.kts): `aiRouter.baseUrl` and `aiRouter.chatModel`.
 */
class AiRouterWiringLiveTest {

    private val baseUrl: String = requiredSystemProperty("aiRouter.baseUrl")

    private val chatModel: String = requiredSystemProperty("aiRouter.chatModel")

    private fun requiredSystemProperty(name: String): String =
        checkNotNull(System.getProperty(name)) {
            "System property '$name' is not set — run via ./gradlew liveTest, " +
                "or set -DaiRouter.baseUrl=... / -DaiRouter.chatModel=..."
        }

    @Test
    @DisplayName("Wiring: send one chat request through the local ai-router and read the response")
    fun chatRoundTrip() = runBlocking {
        AiRouterClient(baseUrl).use { client ->
            val response = client.chat(
                chatRequest(chatModel) {
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
