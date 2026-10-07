package codes.momo.agent.tool

import ai.router.sdk.AiRouterClient
import codes.momo.agent.environment.Privilege
import codes.momo.agent.harness.PROMPT_SUBAGENT_TOOL
import codes.momo.agent.harness.SPAWN_SUBAGENT_TOOL
import codes.momo.agent.harness.SubagentType
import codes.momo.agent.subagent.PromptSubagentTool
import codes.momo.agent.subagent.SpawnSubagentTool
import codes.momo.agent.subagent.Subagents
import codes.momo.agent.web.PageContentsTool
import codes.momo.agent.web.WebSearchTool

/** Every tool an agent can be given: the one place a tool is declared. */
public object ToolCatalog {

    /** The tools a harness lists in `tools`, in display order. The subagent tools come with `subagents` instead. */
    public val listable: List<ToolSpec> = listOf(
        ToolSpec("bash", modelSource = null) { BashTool(it, workspacePath, privilege) },
        ToolSpec("view_image", modelSource = null) { ViewImageTool(it) },
        ToolSpec("web_search", ToolModelSource.Search) { WebSearchTool(it, client) },
        ToolSpec("page_contents", ToolModelSource.Contents) { PageContentsTool(it, client) },
    )

    internal val all: List<ToolSpec> = listable + listOf(
        ToolSpec(SPAWN_SUBAGENT_TOOL, modelSource = null) { SpawnSubagentTool(it, subagents, subagentTypes) },
        ToolSpec(PROMPT_SUBAGENT_TOOL, modelSource = null) { PromptSubagentTool(it, subagents) },
    )

    public fun listed(name: String): ToolSpec? = listable.firstOrNull { it.name == name }
}

internal class ToolDependencies(
    val workspacePath: String,
    val privilege: Privilege,
    val client: AiRouterClient,
    val subagents: Subagents,
    val subagentTypes: Map<String, SubagentType>,
)
