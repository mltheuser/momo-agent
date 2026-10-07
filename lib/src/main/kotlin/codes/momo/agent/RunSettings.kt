package codes.momo.agent

import ai.router.sdk.chat.ReasoningEffort
import kotlinx.serialization.Serializable

/**
 * What a run calls its models with, all travelling with the prompt; the harness sets none.
 * [toolModels] maps a tool name to its model and covers every tool taking a model in the run's harness tree.
 */
@Serializable
public data class RunSettings(
    val model: String,
    val reasoningEffort: ReasoningEffort,
    val toolModels: Map<String, String>,
) {

    /** These settings with each given pin in place of its own value; a null pin inherits. */
    public fun pinnedBy(model: String?, reasoningEffort: ReasoningEffort?): RunSettings =
        copy(model = model ?: this.model, reasoningEffort = reasoningEffort ?: this.reasoningEffort)
}
