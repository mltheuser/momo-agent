package codes.momo.agent.tool

import codes.momo.agent.Subagents
import codes.momo.agent.environment.Privilege
import codes.momo.agent.harness.SubagentType

internal fun coreToolRegistry(
    workspacePath: String,
    privilege: Privilege,
    subagents: Subagents,
    subagentTypes: Map<String, SubagentType>,
): ToolRegistry =
    ToolRegistry(
        listOf(
            BashTool(workspacePath, privilege),
            ViewImageTool(),
            SpawnSubagentTool(subagents, subagentTypes),
            PromptSubagentTool(subagents),
        ),
    )

internal val SUBAGENT_TOOL_NAMES: Set<String> = setOf(SpawnSubagentTool.NAME, PromptSubagentTool.NAME)
