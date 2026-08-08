package codes.momo.agent.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The template endpoints over the wire: a PUT lands as a markdown file under
 * the data directory's `templates/`, the list and the GET read it back, and
 * a name breaking the store's rule never reaches the disk.
 */
class TemplateRoutesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("PUT creates the file, the list sorts, GET reads back, and a second PUT overwrites")
    fun templateLifecycle() {
        withSessionServer(tempDir) { http ->
            assertEquals(emptyList(), http.get("/v1/templates").body<List<String>>())

            val created = http.putTemplate("review", "Review this diff.")
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals(TemplateResponse("review", "Review this diff."), created.body())
            val file = tempDir.resolve("data/templates/review.md")
            assertTrue(file.isRegularFile(), "the template lands as $file")
            assertEquals("Review this diff.", file.readText())

            http.putTemplate("bugfix", "Fix the bug.")
            assertEquals(listOf("bugfix", "review"), http.get("/v1/templates").body<List<String>>())

            val read = http.get("/v1/templates/review")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(TemplateResponse("review", "Review this diff."), read.body())

            http.putTemplate("review", "Review this diff carefully.")
            assertEquals("Review this diff carefully.", tempDir.resolve("data/templates/review.md").readText())
            assertEquals(
                TemplateResponse("review", "Review this diff carefully."),
                http.get("/v1/templates/review").body(),
            )
        }
    }

    @Test
    @DisplayName("GET for a template no file backs is a 404 unknown_template")
    fun unknownTemplateIsA404() {
        withSessionServer(tempDir) { http ->
            val response = http.get("/v1/templates/absent")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("unknown_template", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A directory squatting on a template's file is a 404 unknown_template, not a 500")
    fun directorySquattingOnTheNameIsA404() {
        withSessionServer(tempDir) { http ->
            tempDir.resolve("data/templates/squatter.md").createDirectories()

            val response = http.get("/v1/templates/squatter")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("unknown_template", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A name breaking the rule is a 400 invalid_request on GET and PUT alike")
    fun invalidNamesAreRejected() {
        withSessionServer(tempDir) { http ->
            // %20 is a space, %2E%2E is '..', %2F a path separator; each is
            // one URL segment, so it reaches the route as the {name} value.
            for (encoded in listOf("a%20b", ".hidden", "%2E%2E", "a%2Fb", "%20")) {
                val get = http.get("/v1/templates/$encoded")
                assertEquals(HttpStatusCode.BadRequest, get.status, "GET $encoded")
                assertEquals("invalid_request", get.body<ApiError>().code, "GET $encoded")

                val put = http.putTemplate(encoded, "text")
                assertEquals(HttpStatusCode.BadRequest, put.status, "PUT $encoded")
                assertEquals("invalid_request", put.body<ApiError>().code, "PUT $encoded")
            }
        }
    }

    @Test
    @DisplayName("A blank template body is a 400 invalid_request and nothing lands on disk")
    fun blankBodyIsRejected() {
        withSessionServer(tempDir) { http ->
            val response = http.putTemplate("review", "   ")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
            assertEquals(emptyList(), http.get("/v1/templates").body<List<String>>())
        }
    }
}

private suspend fun HttpClient.putTemplate(name: String, body: String): HttpResponse =
    put("/v1/templates/$name") {
        contentType(ContentType.Application.Json)
        setBody(PutTemplateRequest(body))
    }
