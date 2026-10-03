package codes.momo.agent

import ai.router.sdk.chat.ReasoningEffort
import kotlinx.serialization.Serializable

/** What a run calls the model with. Both travel with the prompt; the harness sets neither. */
@Serializable
public data class RunSettings(
    val model: String,
    val reasoningEffort: ReasoningEffort,
) {

    /** These settings with each given pin in place of its own value; a null pin inherits. */
    public fun pinnedBy(model: String?, reasoningEffort: ReasoningEffort?): RunSettings =
        RunSettings(model ?: this.model, reasoningEffort ?: this.reasoningEffort)
}
