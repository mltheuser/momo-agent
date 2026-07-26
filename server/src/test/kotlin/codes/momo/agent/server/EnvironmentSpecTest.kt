package codes.momo.agent.server

import codes.momo.agent.environment.Privilege
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EnvironmentSpecTest {

    @Test
    @DisplayName("A non-default privilege survives the spec's polymorphic round-trip as its stored wire string")
    fun nonDefaultPrivilegeRoundTrips() {
        // A non-default value, so a dropped field cannot pass for a decoded
        // one; the literal is the persisted `session.json` form, and every
        // string in it is a compatibility contract.
        val spec: EnvironmentSpec = EnvironmentSpec.Local("/workspace", Privilege.PASSWORDLESS_SUDO)

        val json = Json.encodeToString(spec)

        assertEquals("""{"type":"local","workspace":"/workspace","privilege":"passwordless_sudo"}""", json)
        assertEquals(spec, Json.decodeFromString<EnvironmentSpec>(json))
    }
}
