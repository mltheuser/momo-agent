package codes.momo.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class RunSettingsTest {

    @Test
    @DisplayName("A blank model is rejected with IllegalArgumentException")
    fun blankModelIsRejected() {
        assertFailsWith<IllegalArgumentException> { RunSettings(model = "   ") }
    }
}
