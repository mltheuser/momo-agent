package codes.momo.agent.server.rig

import io.ktor.http.ContentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

internal class FileServer private constructor(private val files: Map<String, Served>) : AutoCloseable {

    class Served(val body: ByteArray, val contentType: ContentType)

    private val server: EmbeddedServer<*, *> =
        embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) {
            routing {
                files.forEach { (path, served) ->
                    get(path) { call.respondBytes(served.body, served.contentType) }
                }
            }
        }

    fun url(path: String): String =
        "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}$path"

    override fun close() {
        server.stop()
    }

    companion object {

        fun start(files: Map<String, Served>): FileServer = FileServer(files).also { it.server.start(wait = false) }
    }
}
