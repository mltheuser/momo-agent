package codes.momo.agent

import ai.router.sdk.models.ChatUsage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UsageSumTest {

    @Test
    @DisplayName("ZERO_USAGE is the additive identity")
    fun zeroIsTheAdditiveIdentity() {
        val usage = ChatUsage(1, 2, 3, 4, 5)

        assertEquals(usage, ZERO_USAGE + usage)
        assertEquals(usage, usage + ZERO_USAGE)
    }

    @Test
    @DisplayName("plus sums every field independently")
    fun plusSumsEveryFieldIndependently() {
        val sum = ChatUsage(1, 2, 3, 4, 5) + ChatUsage(10, 20, 30, 40, 50)

        assertEquals(ChatUsage(11, 22, 33, 44, 55), sum)
    }
}
