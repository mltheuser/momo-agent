package codes.momo.agent.server

import codes.momo.agent.FakeLlm
import codes.momo.agent.RunResult
import codes.momo.agent.bashCall
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.toolCallResponse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains

/**
 * The server-side half of the fake router's cannot-hang contract, whose
 * library-side half is `FakeLlmTest`. A run the registry starts is nobody's
 * return value: a request its script cannot answer has to arrive as the run's
 * recorded outcome, because a subscriber waiting on the run's end is the only
 * thing that would otherwise notice — by running out of patience.
 */
class FakeLlmServerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A server-started run outrunning its script ends as an error at once, its diagnostic kept")
    fun runOutrunningItsScriptEndsAtOnce() {
        // One turn is scripted; the tool result it asks for leaves the run
        // needing a second the script does not have.
        val fake = FakeLlm(onOpeningTurn(toolCallResponse(bashCall("call-1", "echo hi"))))

        withFakeSessionServer(tempDir, fake, outrunsItsScript = true) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")

            http.assertRunEndsAtOnce(id, RunResult.Status.ERROR)
            // The outcome carries no text on the wire, so the fake's own
            // record is what names the reply the script was missing.
            assertContains(fake.unansweredRequests.single(), "an opening turn")
        }
    }
}
