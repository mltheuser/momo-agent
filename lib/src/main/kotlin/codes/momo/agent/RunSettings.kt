package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort

public data class RunSettings(
    val model: String,
    val reasoningEffort: ReasoningEffort? = null,
)
