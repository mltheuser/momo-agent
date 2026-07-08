package codes.momo.agent.tool

/**
 * Builds one agent's tool set. Called once per agent — a spawned agent gets its own set by calling
 * again — so per-session state is never shared between agents.
 */
internal fun coreToolRegistry(): ToolRegistry {
    val tracker = FileReadTracker()
    return ToolRegistry(
        listOf(
            BashTool(),
            ReadFileTool(tracker),
            WriteFileTool(tracker),
            EditFileTool(tracker),
            AskUserTool(),
        ),
    )
}
