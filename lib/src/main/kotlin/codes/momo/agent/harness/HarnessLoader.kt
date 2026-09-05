package codes.momo.agent.harness

import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

@Serializable
internal data class HarnessManifest(
    val tools: List<String>,
    val subagents: Map<String, SubagentManifestEntry> = emptyMap(),
)

@Serializable
internal data class SubagentManifestEntry(

    val path: String,
    val description: String,
)

internal object HarnessLoader {

    private const val MANIFEST_NAME = "harness.yaml"
    private const val INSTRUCTIONS_NAME = "instructions.md"

    fun load(folder: Path): Harness {
        val loading = Loading()
        val root = loading.load(folder)
        loading.seal()
        return root
    }

    private class Loading {

        private val loaded = HashMap<Path, Harness>()

        private val pending = mutableListOf<Pair<SubagentType, Path>>()

        fun seal() {
            pending.forEach { (type, childKey) -> type.resolveTo(loaded.getValue(childKey)) }
        }

        fun load(folder: Path): Harness {
            if (!folder.isDirectory()) {
                fail("Harness folder not found (or not a directory): $folder")
            }
            val canonicalFolder = canonical(folder)
            loaded[canonicalFolder]?.let { return it }
            val manifestFile = folder.resolve(MANIFEST_NAME)
            if (!manifestFile.isRegularFile()) {
                fail("Harness at $folder is missing its manifest file: $manifestFile")
            }
            val instructionsFile = folder.resolve(INSTRUCTIONS_NAME)
            if (!instructionsFile.isRegularFile()) {
                fail("Harness at $folder is missing its instructions file: $instructionsFile")
            }
            return loadFiles(folder, canonicalFolder, manifestFile, instructionsFile)
        }

        private fun loadFiles(
            folder: Path,
            canonicalFolder: Path,
            manifestFile: Path,
            instructionsFile: Path,
        ): Harness {
            val manifest = parseManifest(manifestFile)
            val instructions = readFileText(instructionsFile)

            val childFolders = manifest.subagents.mapValues { (type, entry) ->
                if (Path.of(entry.path).isAbsolute) {
                    fail(
                        "$manifestFile: subagent type '$type' uses an absolute path ('${entry.path}') — " +
                            "subagent paths are relative to the declaring harness folder, keeping a " +
                            "harness tree a shareable artifact.",
                    )
                }
                referencedFolder(manifestFile, type, folder.resolve(entry.path).normalize())
            }
            val subagents = manifest.subagents.mapValues { (type, entry) ->
                SubagentType(entry.description).also { pending += it to childFolders.getValue(type) }
            }
            val harness = try {
                Harness(
                    tools = manifest.tools,
                    instructions = instructions,
                    subagents = subagents,
                    folder = canonicalFolder,
                )
            } catch (exception: HarnessValidationException) {
                fail("$manifestFile: ${exception.message}", exception)
            }
            loaded[canonicalFolder] = harness
            childFolders.forEach { (type, childFolder) -> loadReferenced(manifestFile, type, childFolder) }
            return harness
        }

        private fun referencedFolder(manifestFile: Path, type: String, childFolder: Path): Path {
            if (!childFolder.isDirectory()) {
                fail(
                    "$manifestFile: subagent type '$type' references a harness folder that does not " +
                        "exist (or is not a directory): $childFolder",
                )
            }
            return canonical(childFolder)
        }

        private fun loadReferenced(manifestFile: Path, type: String, childFolder: Path) {
            try {
                load(childFolder)
            } catch (exception: HarnessValidationException) {
                fail(
                    "$manifestFile: the harness referenced as subagent type '$type' is broken: " +
                        exception.message,
                    exception,
                )
            }
        }
    }

    private fun parseManifest(manifestFile: Path): HarnessManifest = try {
        Yaml.default.decodeFromString(HarnessManifest.serializer(), readFileText(manifestFile))
    } catch (exception: UnknownPropertyException) {
        if (exception.propertyName == "model") {
            fail(
                "$manifestFile: harness.yaml has no 'model' key; a run's model comes with each prompt.",
                exception,
            )
        } else {
            failInvalidManifest(manifestFile, exception)
        }
    } catch (exception: YamlException) {
        failInvalidManifest(manifestFile, exception)
    }

    private fun failInvalidManifest(manifestFile: Path, exception: YamlException): Nothing = fail(
        "$manifestFile is not a valid harness manifest " +
            "(line ${exception.line}, column ${exception.column}): ${exception.message}",
        exception,
    )

    private fun canonical(folder: Path): Path = try {
        folder.toRealPath()
    } catch (exception: IOException) {
        fail("Harness folder $folder could not be canonicalized (${reason(exception)}).", exception)
    }

    private fun readFileText(file: Path): String = try {
        file.readText()
    } catch (exception: IOException) {
        fail("Harness file $file could not be read (${reason(exception)}).", exception)
    }

    private fun reason(exception: IOException): String =
        exception.message?.let { "${exception.javaClass.simpleName}: $it" } ?: exception.javaClass.simpleName

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw HarnessValidationException(message, cause)
}
