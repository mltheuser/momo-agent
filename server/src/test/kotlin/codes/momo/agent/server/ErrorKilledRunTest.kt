package codes.momo.agent.server

import codes.momo.agent.FakeLlm
import codes.momo.agent.FakeLlmReply
import codes.momo.agent.PlantedError
import codes.momo.agent.RunResult
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onAnyTurn
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A run an `Error` kills still records its outcome. Nobody holds the run's
 * return value ([withFakeSessionServer]), so the throwable ends up at the
 * thread's default handler — printed to stderr, which is this case's accepted
 * end state — leaving the run's event stream as the only place its outcome can
 * live.
 */
class ErrorKilledRunTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A run an Error kills ends the subscriber's stream as ERROR, and the session then reads idle")
    fun errorKilledRunEndsTheStreamAndIdlesTheSession() {
        // The reply is the only seam a server-level case has for raising one:
        // the agents behind the API are built by the registry, so nothing else
        // in the run is the case's to plant.
        val fake = FakeLlm(
            onAnyTurn(
                reply = FakeLlmReply.Thrown { PlantedError("planted in the reply of a server-started run") },
                expectation = "any turn, raising an Error instead of answering",
            ),
        )

        withFakeSessionServer(tempDir, fake) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")

            http.assertRunEndsAtOnce(id, RunResult.Status.ERROR)
        }
    }
}
