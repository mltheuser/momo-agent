package codes.momo.agent.tool

/**
 * Builds the core tool set every harness draws its tool list from. Called
 * once per agent: a future tool holding a session-owned collaborator must
 * never share instances across sessions.
 */
internal fun coreToolRegistry(): ToolRegistry = ToolRegistry(
    listOf(
        BashTool(),
        ReadFileTool(),
        WriteFileTool(),
        EditFileTool(),
    ),
)
