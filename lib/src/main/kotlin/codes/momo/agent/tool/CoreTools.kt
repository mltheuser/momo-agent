package codes.momo.agent.tool

import codes.momo.agent.Subagents

/**
 * Builds the core tool set every harness draws its tool list from. Called
 * once per agent: the subagent tools hold the session-owned [subagents]
 * collaborator, so instances are never shared across sessions.
 */
internal fun coreToolRegistry(subagents: Subagents): ToolRegistry = ToolRegistry(
    listOf(
        BashTool(),
        ReadFileTool(),
        WriteFileTool(),
        EditFileTool(),
        SpawnSubagentTool(subagents),
        PromptSubagentTool(subagents),
    ),
)

/** The tool names withheld from agents at [codes.momo.agent.Budgets.MAX_SUBAGENT_DEPTH]. */
internal val SUBAGENT_TOOL_NAMES: Set<String> = setOf(SpawnSubagentTool.NAME, PromptSubagentTool.NAME)
