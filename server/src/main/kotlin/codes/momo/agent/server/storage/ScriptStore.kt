package codes.momo.agent.server.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class UnknownScriptException(id: String) : RuntimeException("No such script: $id")

internal class InvalidScriptTitleException(title: String) :
    RuntimeException(
        "Invalid script title '$title': a title names the script's file, so it is 1 to $MAX_SCRIPT_TITLE_LENGTH " +
            "characters from [A-Za-z0-9._ -], does not start with '.' and neither starts nor ends with a space.",
    )

/** A script's title is its code file's name and is stored nowhere else. */
internal data class StoredScript(val id: String, val workspace: String, val codeFile: Path) {

    val title: String
        get() = codeFile.nameWithoutExtension

    val folder: Path
        get() = codeFile.parent

    val logFile: Path
        get() = folder.resolve(LOG_FILE)
}

@Serializable
private data class ScriptMetadata(val workspace: String)

internal class ScriptStore(dataDir: Path) {

    private val scriptsDir: Path = dataDir.toAbsolutePath().resolve("scripts")

    fun all(): List<StoredScript> =
        if (scriptsDir.isDirectory()) {
            scriptsDir.listDirectoryEntries().mapNotNull { readOrNull(it.name) }
        } else {
            emptyList()
        }

    fun read(workspace: String, id: String): StoredScript =
        readOrNull(id)?.takeIf { it.workspace == workspace } ?: throw UnknownScriptException(id)

    fun create(workspace: String, title: String): StoredScript {
        val id = UUID.randomUUID().toString()
        val script = StoredScript(id, workspace, codeFile(scriptsDir.resolve(id), title))
        script.folder.createDirectories()
        script.folder.resolve(METADATA_FILE).writeText(Json.encodeToString(ScriptMetadata(workspace)))
        script.codeFile.writeText(STARTER_CODE)
        return script
    }

    fun rename(script: StoredScript, title: String): StoredScript {
        val renamed = script.copy(codeFile = codeFile(script.folder, title))
        // Atomic, because a plain move to a name differing only in letter case does nothing on a
        // case-insensitive volume.
        Files.move(script.codeFile, renamed.codeFile, StandardCopyOption.ATOMIC_MOVE)
        return renamed
    }

    @OptIn(ExperimentalPathApi::class)
    fun delete(script: StoredScript) {
        script.folder.deleteRecursively()
    }

    /** Null for a folder that is no script: without its metadata, or without exactly one code file. */
    private fun readOrNull(id: String): StoredScript? {
        val folder = scriptsDir.resolve(id)
        return try {
            val metadata = Json.decodeFromString<ScriptMetadata>(folder.resolve(METADATA_FILE).readText())
            folder.listDirectoryEntries("*$CODE_EXTENSION").singleOrNull { it.isRegularFile() }
                ?.let { codeFile -> StoredScript(id, metadata.workspace, codeFile) }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }
}

private fun codeFile(folder: Path, title: String): Path {
    val valid = SCRIPT_TITLE_PATTERN.matches(title) && !title.startsWith('.') && title == title.trim()
    if (!valid) {
        throw InvalidScriptTitleException(title)
    }
    return folder.resolve(title + CODE_EXTENSION)
}

private const val MAX_SCRIPT_TITLE_LENGTH = 100

private val SCRIPT_TITLE_PATTERN = Regex("[A-Za-z0-9._ -]{1,$MAX_SCRIPT_TITLE_LENGTH}")

private const val CODE_EXTENSION = ".py"

private const val LOG_FILE = "run.log"

private const val METADATA_FILE = "script.json"

private const val STARTER_CODE = "import momo\n"
