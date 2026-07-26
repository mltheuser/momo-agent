package codes.momo.agent.server

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EnvironmentSpecTest {

    @Test
    @DisplayName("The local spec round-trips as its stored wire form, describing only the workspace")
    fun localSpecRoundTrips() {
        // The literal is the persisted `session.json` form, and every string in
        // it is a compatibility contract. There is no privilege among them: what
        // a command can elevate to follows from the account the server runs as,
        // so it is discovered per environment and reported, never stored.
        val spec: EnvironmentSpec = EnvironmentSpec.Local("/workspace")

        val json = Json.encodeToString(spec)

        assertEquals("""{"type":"local","workspace":"/workspace"}""", json)
        assertEquals(spec, Json.decodeFromString<EnvironmentSpec>(json))
    }
}
