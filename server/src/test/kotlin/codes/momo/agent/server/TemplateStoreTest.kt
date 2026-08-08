package codes.momo.agent.server

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The store's own contract below the routes: the naming rule and what a listing counts. */
class TemplateStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private val store: TemplateStore
        get() = TemplateStore(tempDir)

    @Test
    @DisplayName("Listing a data dir without a templates folder is an empty list, not an error")
    fun missingRootListsEmpty() {
        assertEquals(emptyList(), store.names())
    }

    @Test
    @DisplayName("A listing counts only regular .md files with a valid stem")
    fun listingSkipsWhatIsNotATemplate() {
        val root = tempDir.resolve("templates").createDirectories()
        root.resolve("kept.md").writeText("body")
        root.resolve("notes.txt").writeText("wrong extension")
        root.resolve(".hidden.md").writeText("hidden stem")
        root.resolve("folder.md").createDirectory()

        assertEquals(listOf("kept"), store.names())
    }

    @Test
    @DisplayName("The naming rule: charset, no leading dot, at most 100 chars")
    fun nameValidation() {
        store.write("A-z0.9_ok", "body")
        store.write("a".repeat(100), "body")
        assertEquals("body", store.read("A-z0.9_ok"))

        for (name in listOf("", " ", "a b", ".hidden", "..", "a/b", "a\\b", "ä", "a".repeat(101))) {
            assertFailsWith<InvalidTemplateNameException>("write '$name'") { store.write(name, "body") }
            assertFailsWith<InvalidTemplateNameException>("read '$name'") { store.read(name) }
        }
    }

    @Test
    @DisplayName("Reading a template no file backs is UnknownTemplateException")
    fun missingTemplateIsTyped() {
        assertFailsWith<UnknownTemplateException> { store.read("absent") }
    }
}
