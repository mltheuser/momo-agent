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

/**
 * Wire format of `harness.yaml` v1. kaml's strict mode (the default) rejects
 * unknown keys, keeping the format deliberate.
 */
@Serializable
internal data class HarnessManifest(
    val tools: List<String>,
)

/**
 * Loads a harness folder into a validated [Harness]; every failure is a
 * [HarnessValidationException] naming the offending file.
 */
internal object HarnessLoader {

    private const val MANIFEST_NAME = "harness.yaml"
    private const val INSTRUCTIONS_NAME = "instructions.md"

    fun load(folder: Path): Harness {
        if (!folder.isDirectory()) {
            fail("Harness folder not found (or not a directory): $folder")
        }
        val manifestFile = folder.resolve(MANIFEST_NAME)
        if (!manifestFile.isRegularFile()) {
            fail("Harness at $folder is missing its manifest file: $manifestFile")
        }
        val instructionsFile = folder.resolve(INSTRUCTIONS_NAME)
        if (!instructionsFile.isRegularFile()) {
            fail("Harness at $folder is missing its instructions file: $instructionsFile")
        }

        val manifest = parseManifest(manifestFile)
        val instructions = readFileText(instructionsFile)
        return try {
            Harness(
                tools = manifest.tools,
                instructions = instructions,
            )
        } catch (exception: HarnessValidationException) {
            // Invariant errors from Harness's init lack the file context.
            fail("$manifestFile: ${exception.message}", exception)
        }
    }

    private fun parseManifest(manifestFile: Path): HarnessManifest = try {
        Yaml.default.decodeFromString(HarnessManifest.serializer(), readFileText(manifestFile))
    } catch (exception: UnknownPropertyException) {
        // Matched on the key itself, so every value form — including null —
        // draws the retirement message.
        if (exception.propertyName == "model") {
            fail(
                "$manifestFile: the 'model' key is no longer part of harness.yaml — a harness is " +
                    "instructions plus tools, and a run's model comes with each prompt.",
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

    private fun readFileText(file: Path): String = try {
        file.readText()
    } catch (exception: IOException) {
        val reason = exception.message?.let { "${exception.javaClass.simpleName}: $it" }
            ?: exception.javaClass.simpleName
        fail("Harness file $file could not be read ($reason).", exception)
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw HarnessValidationException(message, cause)
}
