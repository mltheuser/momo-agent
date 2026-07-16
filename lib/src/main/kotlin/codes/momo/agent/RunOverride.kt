package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort

/**
 * Per-run override of the harness's model settings: [model] replaces the
 * harness model for the run, [reasoningEffort] sets the run's LLM calls'
 * effort; a null field keeps the harness default. Child runs the parent
 * drives during an overridden run (through the subagent tools) inherit
 * that run's override; a child prompted through its own [Agent.send] uses
 * only that call's.
 */
public data class RunOverride(
    val model: String? = null,
    val reasoningEffort: ReasoningEffort? = null,
)
