package codes.momo.agent.server

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {

    @Test
    @DisplayName("Each setting resolves CLI argument over environment variable over default")
    fun argumentBeatsEnvironmentBeatsDefault() {
        val env = mapOf("MOMO_AGENT_PORT" to "9000", "MOMO_AGENT_DATA_DIR" to "/tmp/env-data")

        val fromArgs = ServerConfig.resolve(listOf("--port=9100", "--ai-router-base-url=http://localhost:1"), env)
        assertEquals(9100, fromArgs.port)
        assertEquals(Path.of("/tmp/env-data"), fromArgs.dataDir)
        assertEquals("http://localhost:1", fromArgs.aiRouterBaseUrl)

        val fromDefaults = ServerConfig.resolve(emptyList(), emptyMap())
        assertEquals(ServerConfig.DEFAULT_PORT, fromDefaults.port)
        assertEquals(ServerConfig.DEFAULT_DATA_DIR, fromDefaults.dataDir)
        assertEquals(ServerConfig.DEFAULT_AI_ROUTER_BASE_URL, fromDefaults.aiRouterBaseUrl)
    }

    @Test
    @DisplayName("Unknown and malformed arguments fail with a clear message")
    fun invalidArgumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.resolve(listOf("--nope=1"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.resolve(listOf("port=9100"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.resolve(listOf("--port=nan"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.resolve(listOf("--port=0"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.resolve(listOf("--port=70000"), emptyMap()) }
    }
}
