package codes.momo.agent.tool

/** A tool as a harness names it. [modelSource] is where its model options come from, if it runs on a model. */
public class ToolSpec internal constructor(
    public val name: String,
    public val modelSource: ToolModelSource<*>?,
    private val factory: ToolDependencies.(ToolSpec) -> Tool<*>,
) {

    init {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(name.none { it.isWhitespace() }) { "Tool name must not contain whitespace: '$name'." }
    }

    internal fun toolFor(dependencies: ToolDependencies): Tool<*> = dependencies.factory(this)
}
