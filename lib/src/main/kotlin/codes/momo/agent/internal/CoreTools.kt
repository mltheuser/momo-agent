package codes.momo.agent.internal

import ai.router.sdk.AiRouterClient
import codes.momo.agent.environment.Privilege
import codes.momo.agent.harness.SubagentType
import codes.momo.agent.subagent.PromptSubagentTool
import codes.momo.agent.subagent.SpawnSubagentTool
import codes.momo.agent.subagent.Subagents
import codes.momo.agent.tool.BashTool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ViewImageTool
import codes.momo.agent.web.PageContentsTool
import codes.momo.agent.web.WebSearchTool

internal fun coreToolRegistry(
    workspacePath: String,
    privilege: Privilege,
    subagents: Subagents,
    subagentTypes: Map<String, SubagentType>,
    client: AiRouterClient,
): ToolRegistry =
    ToolRegistry(
        listOf(
            BashTool(workspacePath, privilege),
            ViewImageTool(),
            WebSearchTool(client),
            PageContentsTool(client),
            SpawnSubagentTool(subagents, subagentTypes),
            PromptSubagentTool(subagents),
        ),
    )
