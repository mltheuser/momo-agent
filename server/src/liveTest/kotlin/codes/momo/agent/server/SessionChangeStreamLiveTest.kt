package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.harness.harnessPath
import io.ktor.client.request.delete
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The change stream against the real server process: which mutations reach a
 * subscriber, and that a read taken on a frame's heels already agrees with it.
 * Only the run case reaches a model, and any model answering at all serves it.
 *
 * Every case counts the stream's own opening frame, which `withChangeStream`
 * has already waited for — so the mutations below it are behind the
 * subscription rather than racing it.
 */
class SessionChangeStreamLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A run signals twice — as it starts and as it ends — and the closing frame already reads idle")
    fun aRunSignalsAtBothEnds() {
        withLiveServer { http ->
            val id = http.createSession(liveHarness(tempDir), localWorkspace(tempDir, "signalled")).id

            http.withChangeStream { stream ->
                assertEquals(1, stream.frames, "the create came before the subscription")

                http.prompt(id, READY_PROMPT)
                stream.awaitFrames(2) // The run started.
                assertEquals(SessionStatus.RUNNING, http.sessionInfo(id).status)

                stream.awaitFrames(3) // The run ended.
                // The closing frame stands behind the release of the run's
                // claim, so a status read on its heels already sees the run
                // over — the ordering the client's refresh-on-a-frame needs.
                assertEquals(SessionStatus.IDLE, http.sessionInfo(id).status)
            }

            assertIs<AgentEvent.RunFinished>(http.streamEvents(id).last().event)
        }
    }

    @Test
    @DisplayName("Create, rename, close and delete each signal once; reading the listing signals nothing")
    fun everyListingMutationSignalsOnce() {
        withLiveServer { http ->
            http.withChangeStream { stream ->
                // One mutation at a time, each awaited before the next: a
                // signal that stopped arriving, and one arriving twice, both
                // fail here rather than cancelling out in a total.
                val workspace = localWorkspace(tempDir, "listed")
                val id = http.createSession(harnessPath(tempDir), workspace).id
                stream.awaitFrames(2)

                http.renameSession(id, "named")
                stream.awaitFrames(3)

                http.closeSession(id)
                stream.awaitFrames(4)

                http.sessions(workspace)
                http.sessionInfo(id)

                http.delete("/v1/sessions/$id")
                // The reads above signalled nothing: their frames would have
                // filled this count early, leaving the delete's own stranded.
                stream.awaitFrames(5)
            }
        }
    }

    @Test
    @DisplayName("A close whose caller is gone still signals: the announcement outlives the request")
    fun aCloseSignalsEvenWhenItsCallerIsGone() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir, "abandoned")).id

            http.withChangeStream { stream ->
                assertEquals(1, stream.frames, "the create came before the subscription")

                // The close's teardown is shielded from its caller's
                // cancellation so a live environment is never abandoned; its
                // announcement has to be shielded with it, or a disconnecting
                // client leaves every other client's listing stale for good.
                http.abandonedClose(id)

                stream.awaitFrames(2)
                assertEquals(SessionStatus.CLOSED, http.sessionInfo(id).status)
            }
        }
    }
}

/** A prompt that is over in one turn: this case is about the frames, not the work. */
private const val READY_PROMPT: String = "Reply with the single word: ready."
