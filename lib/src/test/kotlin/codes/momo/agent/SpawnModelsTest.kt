package codes.momo.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SpawnModelsTest {

    @Test
    @DisplayName("A short query ranks the entry containing its best-matching stretch first, case-insensitively")
    fun shortQueryFindsItsEntry() {
        val ranked = closestModels(
            "Opus 5",
            listOf(
                "qwen/qwen3.5-35b-a3b:cloud@openrouter",
                "anthropic/claude-opus-5:cloud@openrouter",
                "google/gemini-3-pro:cloud@openrouter",
            ),
        )

        assertEquals("anthropic/claude-opus-5:cloud@openrouter", ranked.first())
    }

    @Test
    @DisplayName("The suggestion list caps at ten entries")
    fun suggestionsCapAtTen() {
        val candidates = (1..30).map { "model-$it:cloud@provider" }

        assertEquals(10, closestModels("model", candidates).size)
    }

    @Test
    @DisplayName("Ties break by length then lexicographically, so the ranking is deterministic")
    fun tiesBreakDeterministically() {
        // Every candidate contains the query verbatim: distance 0 across the board.
        val ranked = closestModels("m", listOf("mbb:cloud@p", "ma:cloud@p", "mb:cloud@p", "maa:cloud@p"))

        assertEquals(listOf("ma:cloud@p", "mb:cloud@p", "maa:cloud@p", "mbb:cloud@p"), ranked)
    }

    @Test
    @DisplayName("The distance is the query's edits against the candidate's best substring")
    fun distanceIsAgainstTheBestSubstring() {
        assertEquals(0, approximateSubstringDistance("opus", "anthropic/claude-opus-5:cloud@openrouter"))
        assertEquals(1, approximateSubstringDistance("opas", "anthropic/claude-opus-5:cloud@openrouter"))
        assertEquals(1, approximateSubstringDistance("opus 5", "anthropic/claude-opus-5:cloud@openrouter"))
        assertEquals(0, approximateSubstringDistance("", "anything"))
        assertEquals(3, approximateSubstringDistance("abc", ""))
    }
}
