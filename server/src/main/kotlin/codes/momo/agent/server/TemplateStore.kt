package codes.momo.agent.server

import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** Thrown when a request names a template no file backs. */
internal class UnknownTemplateException(name: String) : RuntimeException("No such template: $name")

/**
 * Thrown when a client-supplied template name breaks the naming rule; the
 * message names the rule.
 */
internal class InvalidTemplateNameException(name: String) :
    RuntimeException(
        "Invalid template name '$name': a name is 1 to $MAX_TEMPLATE_NAME_LENGTH characters from " +
            "[A-Za-z0-9._-] and does not start with '.'.",
    )

/**
 * Prompt templates on disk — global, never workspace-scoped: one markdown
 * file per template under the data directory's `templates/`, named
 * `<name>.md` and holding the template body verbatim. The store never
 * deletes: users remove a template by deleting its file.
 *
 * A name is 1 to [MAX_TEMPLATE_NAME_LENGTH] characters from `[A-Za-z0-9._-]`
 * and does not start with `.` — which keeps every name a plain visible file
 * stem, free of whitespace, path separators and `..`. Every path taking a
 * client-supplied name enforces the rule with [InvalidTemplateNameException].
 */
internal class TemplateStore(dataDir: Path) {

    private val templatesDir: Path = dataDir.resolve("templates")

    /** Every stored template's name, sorted; a missing `templates/` is an empty list. */
    fun names(): List<String> =
        if (templatesDir.isDirectory()) {
            templatesDir.listDirectoryEntries("*$TEMPLATE_EXTENSION")
                .filter { it.isRegularFile() && it.nameWithoutExtension.isValidTemplateName() }
                .map { it.nameWithoutExtension }
                .sorted()
        } else {
            emptyList()
        }

    /**
     * The stored body of [name].
     *
     * @throws UnknownTemplateException when no regular file backs [name] —
     * a directory squatting on the name is as unknown as nothing at all.
     */
    fun read(name: String): String {
        val source = file(name)
        if (!source.isRegularFile()) {
            throw UnknownTemplateException(name)
        }
        return try {
            source.readText()
        } catch (_: NoSuchFileException) {
            // Deleted by hand between the check and the read: still unknown.
            throw UnknownTemplateException(name)
        }
    }

    /** Stores [body] as [name], creating or replacing its file atomically. */
    fun write(name: String, body: String) {
        val target = file(name)
        templatesDir.createDirectories()
        replaceAtomically(target, body)
    }

    private fun file(name: String): Path {
        if (!name.isValidTemplateName()) {
            throw InvalidTemplateNameException(name)
        }
        return templatesDir.resolve(name + TEMPLATE_EXTENSION)
    }
}

private fun String.isValidTemplateName(): Boolean =
    length <= MAX_TEMPLATE_NAME_LENGTH && !startsWith('.') && TEMPLATE_NAME_PATTERN.matches(this)

private val TEMPLATE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

private const val MAX_TEMPLATE_NAME_LENGTH = 100

private const val TEMPLATE_EXTENSION = ".md"
