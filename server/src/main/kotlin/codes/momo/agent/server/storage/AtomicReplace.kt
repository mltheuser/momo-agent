package codes.momo.agent.server.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

internal fun replaceAtomically(target: Path, content: String) {
    val staging = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    staging.writeText(content)
    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
}
