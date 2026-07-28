package codes.momo.agent.server

import codes.momo.agent.assistantResponse
import codes.momo.agent.harness.writeHarness
import codes.momo.agent.onOpeningTurn
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class StoredMetadataTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A stored session whose metadata still carries the obsolete privilege field loads and rebuilds")
    fun obsoletePrivilegeFieldInStoredMetadataIsIgnored() {
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = tempDir.resolve("workspace").createDirectories().toString()
        val folder = tempDir.resolve("data/sessions/old-session").createDirectories()
        // Written while the spec still accepted a declared privilege. The file
        // outlives that schema, so the key must be ignored, not fail the load.
        folder.resolve("session.json").writeText(
            """{"type":"root","harnessPath":"$harness","environment":{"type":"local",""" +
                """"workspace":"$workspace","privilege":"passwordless_sudo"}}""",
        )
        folder.resolve("events.jsonl").writeText(
            """{"type":"session_started","sequenceId":0,"timestampMillis":0,""" +
                """"sessionId":"old-session","title":"Older than the field"}""" + "\n",
        )

        withFakeSessionServer(
            tempDir,
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "resumed")),
        ) { http ->
            val info = http.get("/v1/sessions/old-session").body<SessionInfo>()
            assertEquals(EnvironmentSpec.Local(workspace), info.environment)

            // The rebuild half: the next prompt constructs the environment from that spec.
            http.prompt("old-session", "carry on")
            http.awaitRunEnd("old-session")
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/old-session").body<SessionInfo>().status)
        }
    }
}
