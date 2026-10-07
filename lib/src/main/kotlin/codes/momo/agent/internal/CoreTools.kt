package codes.momo.agent.internal

import codes.momo.agent.tool.ToolCatalog
import codes.momo.agent.tool.ToolDependencies
import codes.momo.agent.tool.ToolRegistry

internal fun coreToolRegistry(dependencies: ToolDependencies): ToolRegistry =
    ToolRegistry(ToolCatalog.all.map { it.toolFor(dependencies) })
