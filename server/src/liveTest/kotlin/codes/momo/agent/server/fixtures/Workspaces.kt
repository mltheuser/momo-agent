package codes.momo.agent.server.fixtures

import java.nio.file.Path
import kotlin.io.path.createDirectories

internal fun localWorkspace(tempDir: Path, name: String = "workspace"): String =
    tempDir.resolve(name).createDirectories().toString()
