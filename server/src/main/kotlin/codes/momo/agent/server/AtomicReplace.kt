package codes.momo.agent.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/**
 * Replaces [target] with [content] via a temp file moved atomically over it:
 * concurrent readers hold no lock, so they must only ever see a complete
 * file — old or new.
 */
internal fun replaceAtomically(target: Path, content: String) {
    val staging = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    staging.writeText(content)
    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
}
