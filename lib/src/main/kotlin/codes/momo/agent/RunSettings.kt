package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort

/**
 * One run's model settings: [model] is the model every LLM call of the
 * run goes to, [reasoningEffort] those calls' effort. Every run carries
 * its own settings — there is no harness or session default. Child runs
 * the parent drives during a run (through the subagent tools) inherit
 * that run's settings; a child prompted through its own [Agent.send]
 * uses only that call's.
 */
public data class RunSettings(
    val model: String,
    val reasoningEffort: ReasoningEffort? = null,
) {

    init {
        require(model.isNotBlank()) { "A model must not be blank." }
    }
}
