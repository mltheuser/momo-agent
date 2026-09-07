package codes.momo.agent.server.rig

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

internal fun withLiveServer(block: suspend (HttpClient) -> Unit): Unit = runBlocking {
    liveHttpClient(sharedLiveServer.baseUrl).use { http -> block(http) }
}

internal val sharedLiveServer: LiveServerProcess get() = sharedStart.getOrThrow()

@OptIn(ExperimentalPathApi::class)
private val sharedStart: Result<LiveServerProcess> by lazy {
    val dataDir = Files.createTempDirectory("momo-live-server")
    runCatching { LiveServerProcess.start(dataDir) }
        .onSuccess { server ->

            Runtime.getRuntime().addShutdownHook(
                Thread {
                    server.close()
                    dataDir.deleteRecursively()
                },
            )
        }
        .onFailure { dataDir.deleteRecursively() }
}
