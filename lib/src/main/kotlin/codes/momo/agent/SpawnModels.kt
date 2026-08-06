package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.Capability
import ai.router.sdk.models.ModelInfo
import ai.router.sdk.models.ModelList
import kotlinx.coroutines.CancellationException

/**
 * The router's catalog filtered to the models an agent run can use —
 * capabilities including both chat and tools. ai-router's server-side
 * capability filter takes a single capability, so tools is filtered here.
 * Spawn-time model validation and the agent server's model listing both
 * read this one filter.
 */
public suspend fun AiRouterClient.usableModels(): ModelList {
    val catalog = listModels(capability = Capability.CHAT)
    return catalog.copy(data = catalog.data.filter { it.hasCapability(Capability.TOOLS) })
}

/**
 * Validates a spawn's model_id against [usableModels]: an id is valid only
 * as a catalog entry's fully-qualified `model` string, with or without its
 * `@provider` suffix — exactly the forms the router resolves. Consulted only
 * when a spawn pins a model, so the inherit path never fetches the catalog.
 */
internal class SpawnModels(private val client: AiRouterClient) {

    /** Why [modelId] cannot be spawned with — null when it can. */
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

/** Whether this id addresses [entry]: its `model` string verbatim, or that string without the `@provider` suffix. */
private fun String.addresses(entry: ModelInfo): Boolean =
    this == entry.model || this == entry.model.substringBeforeLast('@')

private fun unknownModelMessage(modelId: String, usable: List<ModelInfo>): String = buildString {
    append("model_id '").append(modelId).append("' is not in the router's catalog. Closest usable models:")
    val suggestions = closestModels(modelId, usable.map { it.model })
    if (suggestions.isEmpty()) append(" (none)")
    suggestions.forEach { append("\n- ").append(it) }
    append("\nPass one of these verbatim; the '@provider' suffix may be dropped to let the router choose.")
}

/** Up to [limit] candidates closest to [query] under [approximateSubstringDistance], deterministically ordered. */
internal fun closestModels(query: String, candidates: List<String>, limit: Int = SUGGESTION_LIMIT): List<String> {
    val distance = candidates.associateWith { approximateSubstringDistance(query, it) }
    return candidates
        .sortedWith(compareBy({ distance.getValue(it) }, { it.length }, { it }))
        .take(limit)
}

/**
 * Edit distance from [query] to the closest substring of [candidate],
 * case-insensitively: Levenshtein where the candidate's leading characters
 * are free (row zero stays 0) and so are its trailing ones (the answer is
 * the last row's minimum) — so a short query like "opus 5" scores by the
 * best-matching stretch of a long fully-qualified id.
 */
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
