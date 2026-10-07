package codes.momo.agent.tool

import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment

/** What a tool call runs within: the agent's environment and the settings of the run making the call. */
internal class ToolContext(val environment: ExecutionEnvironment, val settings: RunSettings) {

    /** The run's model for [spec]; a run starts only when its settings cover every tool taking a model. */
    fun modelFor(spec: ToolSpec): String = settings.toolModels.getValue(spec.name)
}
