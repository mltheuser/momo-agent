package codes.momo.agent.server

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's HTTP surface short of a chat completion, against the real
 * server process: none of it reaches a model, so these cases cost the suite
 * milliseconds and share its one process — each owning only what it creates.
 */
class SessionSurfaceLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "Templates: PUT lands as a file, the list sorts, GET reads back, a blank body is 400, a missing name 404"
    )
    fun templates() = withLiveServer { http ->
        // Names unique to this case: the shared process's template store is global.
        val review = "surface-review"
        val bugfix = "surface-bugfix"
        val file = sharedLiveServer.dataDir.resolve("templates/$review.md")

        assertEquals(TemplateResponse(review, "Review this diff."), http.putTemplate(review, "Review this diff."))
        assertTrue(file.isRegularFile(), "the template lands as $file")
        assertEquals("Review this diff.", file.readText())
        http.putTemplate(bugfix, "Fix the bug.")
        assertEquals(listOf(bugfix, review), http.templateNames().filter { it.startsWith("surface-") }, "sorted")

        val read = http.templateResponse(review)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(TemplateResponse(review, "Review this diff."), read.body())
        http.putTemplate(review, "Review this diff carefully.")
        assertEquals("Review this diff carefully.", file.readText(), "a second PUT overwrites")

        val blank = http.putTemplateResponse(review, "   ")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("invalid_request", blank.body<ApiError>().code)
        assertEquals("Review this diff carefully.", file.readText(), "a rejected body leaves the file alone")

        val missing = http.templateResponse("surface-no-such-template")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("unknown_template", missing.body<ApiError>().code)
    }
}
