package codes.momo.agent.tool

import codes.momo.agent.RunSettings
import codes.momo.agent.environment.ExecutionEnvironment

/** What a tool call runs within: the agent's environment and the settings of the run making the call. */
internal class ToolContext(val environment: ExecutionEnvironment, val settings: RunSettings)
