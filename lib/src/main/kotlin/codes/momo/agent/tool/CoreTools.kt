package codes.momo.agent.tool

import codes.momo.agent.Subagents
import codes.momo.agent.harness.SubagentType

/**
 * Builds the core tool set every harness draws its tool list from. Called
 * once per agent: [workspacePath] is the executing environment's workspace
 * root, which the bash tool states in its description, and the subagent
 * tools hold the session-owned [subagents] collaborator and enumerate the
 * harness's [subagentTypes], so instances are never shared across sessions.
 */
internal fun coreToolRegistry(
    workspacePath: String,
    subagents: Subagents,
    subagentTypes: Map<String, SubagentType>,
): ToolRegistry =
    ToolRegistry(
        listOf(
            BashTool(workspacePath),
            SpawnSubagentTool(subagents, subagentTypes),
            PromptSubagentTool(subagents),
        ),
    )

/** The subagent tool names; when they are offered is the agent loop's call over its harness and depth. */
internal val SUBAGENT_TOOL_NAMES: Set<String> = setOf(SpawnSubagentTool.NAME, PromptSubagentTool.NAME)
