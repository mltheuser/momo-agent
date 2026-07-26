package codes.momo.agent.environment

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PrivilegeTest {

    @Test
    @DisplayName("Every privilege encodes to its frozen wire name and decodes back from it")
    fun everyPrivilegeRoundTripsThroughItsWireName() {
        // These strings are stored session state: each session.json holds one
        // verbatim inside its environment spec. Renaming an entry — or losing
        // the @Serializable that makes the entry names apply at all, which
        // would silently fall back to the Kotlin names — stops every session
        // written before the change from loading.
        val wireNames = mapOf(
            Privilege.ROOT to "\"root\"",
            Privilege.PASSWORDLESS_SUDO to "\"passwordless_sudo\"",
            Privilege.UNPRIVILEGED to "\"unprivileged\"",
        )

        assertEquals(Privilege.entries.toSet(), wireNames.keys, "every privilege is pinned")
        wireNames.forEach { (privilege, wire) ->
            assertEquals(wire, Json.encodeToString(privilege))
            assertEquals(privilege, Json.decodeFromString<Privilege>(wire))
        }
    }
}
