package codes.momo.agent.internal

import codes.momo.agent.environment.Privilege
import codes.momo.agent.harness.SubagentType
import codes.momo.agent.subagent.PromptSubagentTool
import codes.momo.agent.subagent.SpawnSubagentTool
import codes.momo.agent.subagent.Subagents
import codes.momo.agent.tool.BashTool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ViewImageTool

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
