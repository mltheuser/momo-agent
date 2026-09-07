package codes.momo.agent.subagent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ModelInfo
import codes.momo.agent.usableModels
import kotlinx.coroutines.CancellationException

internal class SpawnModels(private val client: AiRouterClient) {

    suspend fun rejectionFor(modelId: String): String? {
        val usable = try {
            client.usableModels().data
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            return "model_id '$modelId' could not be validated against the router's catalog: $exception"
        }
        return if (usable.any { modelId.addresses(it) }) null else unknownModelMessage(modelId, usable)
    }
}

private fun String.addresses(entry: ModelInfo): Boolean =
    this == entry.model || this == entry.model.substringBeforeLast('@')

private fun unknownModelMessage(modelId: String, usable: List<ModelInfo>): String = buildString {
    append("model_id '").append(modelId).append("' is not in the router's catalog. Closest usable models:")
    val suggestions = closestModels(modelId, usable.map { it.model })
    if (suggestions.isEmpty()) append(" (none)")
    suggestions.forEach { append("\n- ").append(it) }
    append("\nPass one of these verbatim; the '@provider' suffix may be dropped to let the router choose.")
}

internal fun closestModels(query: String, candidates: List<String>, limit: Int = SUGGESTION_LIMIT): List<String> {
    val distance = candidates.associateWith { approximateSubstringDistance(query, it) }
    return candidates
        .sortedWith(compareBy({ distance.getValue(it) }, { it.length }, { it }))
        .take(limit)
}

internal fun approximateSubstringDistance(query: String, candidate: String): Int {
    val q = query.lowercase()
    val c = candidate.lowercase()
    var previous = IntArray(c.length + 1)
    var current = IntArray(c.length + 1)
    for (i in 1..q.length) {
        current[0] = i
        for (j in 1..c.length) {
            val substitution = previous[j - 1] + if (q[i - 1] == c[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous.min()
}

private const val SUGGESTION_LIMIT: Int = 10
