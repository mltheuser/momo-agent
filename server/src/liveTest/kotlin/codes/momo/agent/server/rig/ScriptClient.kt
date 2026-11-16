package codes.momo.agent.server.rig

import codes.momo.agent.server.TitleRequest
import codes.momo.agent.server.script.ScriptInfo
import codes.momo.agent.server.script.ScriptState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.assertEquals

/** The script API as one workspace's client sees it. */
internal class ScriptClient(private val http: HttpClient, private val workspace: String) {

    suspend fun list(): List<ScriptInfo> = http.get("/v1/scripts") { parameter("workspace", workspace) }.body()

    suspend fun create(title: String): ScriptInfo = createResponse(title).answered(HttpStatusCode.Created)

    suspend fun createResponse(title: String): HttpResponse = post("", TitleRequest(title))

    suspend fun rename(id: String, title: String): ScriptInfo = renameResponse(id, title).answered()

    suspend fun renameResponse(id: String, title: String): HttpResponse = post("/$id/rename", TitleRequest(title))

    suspend fun start(id: String): ScriptInfo = startResponse(id).answered()

    suspend fun startResponse(id: String): HttpResponse = post("/$id/start")

    suspend fun stop(id: String): ScriptInfo = stopResponse(id).answered()

    suspend fun stopResponse(id: String): HttpResponse = post("/$id/stop")

    suspend fun deleteResponse(id: String): HttpResponse =
        http.delete("/v1/scripts/$id") { parameter("workspace", workspace) }

    suspend fun awaitState(id: String, state: ScriptState): ScriptInfo =
        awaited("script $id becoming $state") { list().singleOrNull { it.id == id && it.state == state } }

    private suspend fun post(path: String, body: TitleRequest? = null): HttpResponse =
        http.post("/v1/scripts$path") {
            parameter("workspace", workspace)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> HttpResponse.answered(expected: HttpStatusCode = HttpStatusCode.OK): T {
        assertEquals(expected, status, bodyAsText())
        return body()
    }
}
