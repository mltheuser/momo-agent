package codes.momo.agent.tool

/**
 * Session-scoped record of the file paths whose content the model has seen,
 * shared by the tools that read files and guard overwrites.
 *
 * Once marked, a path stays read for the whole session: there is deliberately no staleness tracking.
 * Not thread-safe; tool calls run sequentially by design.
 */
public class FileReadTracker {

    private val readPaths: MutableSet<String> = mutableSetOf()

    /** Records [path] as read this session. */
    public fun markRead(path: String) {
        readPaths += path
    }

    /** Whether [path] was marked read this session. */
    public fun wasRead(path: String): Boolean = path in readPaths
}
