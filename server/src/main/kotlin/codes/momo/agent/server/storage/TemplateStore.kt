package codes.momo.agent.server.storage

import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

internal class UnknownTemplateException(name: String) : RuntimeException("No such template: $name")

internal class InvalidTemplateNameException(name: String) :
    RuntimeException(
        "Invalid template name '$name': a name is 1 to $MAX_TEMPLATE_NAME_LENGTH characters from " +
            "[A-Za-z0-9._-] and does not start with '.'.",
    )

internal class TemplateStore(dataDir: Path) {

    private val templatesDir: Path = dataDir.resolve("templates")

    fun names(): List<String> =
        if (templatesDir.isDirectory()) {
            templatesDir.listDirectoryEntries("*$TEMPLATE_EXTENSION")
                .filter { it.isRegularFile() && it.nameWithoutExtension.isValidTemplateName() }
                .map { it.nameWithoutExtension }
                .sorted()
        } else {
            emptyList()
        }

    fun read(name: String): String {
        val source = file(name)
        if (!source.isRegularFile()) {
            throw UnknownTemplateException(name)
        }
        return try {
            source.readText()
        } catch (_: NoSuchFileException) {
            throw UnknownTemplateException(name)
        }
    }

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
