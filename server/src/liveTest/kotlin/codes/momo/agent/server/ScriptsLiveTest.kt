package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.liveHarness
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.LiveServerProcess
import codes.momo.agent.server.rig.ScriptClient
import codes.momo.agent.server.rig.assertRejected
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.awaited
import codes.momo.agent.server.rig.liveHttpClient
import codes.momo.agent.server.rig.liveSettings
import codes.momo.agent.server.rig.putTemplate
import codes.momo.agent.server.rig.sessions
import codes.momo.agent.server.rig.withChangeStream
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.script.ScriptState
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptsLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "A started script creates a prompted session from a template and keeps a file beside its code; " +
            "a running script refuses rename and delete, and a stop leaves it inactive"
    )
    fun aStartedScriptCreatesASession() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        val scripts = ScriptClient(http, workspace)
        http.putTemplate("daily-focus", "Reply with the single word: ready. Ignore this date: {{date}}.")

        val created = http.withChangeStream { stream ->
            stream.signalled("creating a script") { scripts.create("Daily focus") }
        }
        assertEquals(ScriptState.INACTIVE, created.state, "a new script is not started")
        val code = Path.of(created.codePath)
        assertEquals("Daily focus.py", code.fileName.toString(), "the code file carries the title")
        assertEquals(listOf(created), scripts.list())
        code.writeText(focusOnceScript(liveHarness(tempDir)))
        assertEquals(emptyList(), http.sessions(workspace), "saving the code starts nothing")

        assertEquals(ScriptState.RUNNING, scripts.start(created.id).state)
        val session = awaited("the script creating its session") { http.sessions(workspace).singleOrNull() }
        assertEquals("Focus today", session.title, "the script's title")
        val finished = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(session.id).last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(finished.finalMessage.orEmpty(), "ready", ignoreCase = true, message = "the template's prompt")
        assertTrue(code.resolveSibling("planned.txt").exists(), "a relative path lands beside the code")

        scripts.startResponse(created.id).assertRejected("conflict", "a second start", status = HttpStatusCode.Conflict)
        scripts.renameResponse(created.id, "Other")
            .assertRejected("conflict", "renaming a running script", status = HttpStatusCode.Conflict)
        scripts.deleteResponse(created.id)
            .assertRejected("conflict", "deleting a running script", status = HttpStatusCode.Conflict)

        assertEquals(ScriptState.INACTIVE, scripts.stop(created.id).state, "a stop answers once the process is gone")
        assertEquals(ScriptState.INACTIVE, scripts.stop(created.id).state, "stopping an inactive script is a no-op")
    }

    @Test
    @DisplayName(
        "A script that raises is failed with its traceback in the log, and a refused prompt leaves no session; " +
            "the next start clears the mark; the code file's name is the title; delete removes the folder"
    )
    fun aFailedRunIsMarkedUntilTheNextStart() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        val scripts = ScriptClient(http, workspace)
        val harness = liveHarness(tempDir)
        val created = scripts.create("Refused")
        val folder = Path.of(created.codePath).parent
        Path.of(created.codePath).writeText(
            """
            import momo
            momo.create_session(title="t", harness="$harness", model="m", reasoning_effort="low", prompt=" ")
            """.trimIndent(),
        )

        scripts.start(created.id)
        val failed = scripts.awaitState(created.id, ScriptState.FAILED)
        assertContains(Path.of(failed.logPath).readText(), "momo.Error: invalid_request", message = "the traceback")
        assertEquals(emptyList(), http.sessions(workspace), "the session of a refused prompt is deleted")

        Path.of(created.codePath).writeText("import time\ntime.sleep(600)\n")
        assertEquals(ScriptState.RUNNING, scripts.start(created.id).state, "a start clears the failure")
        assertEquals("", Path.of(failed.logPath).readText(), "each start begins the log anew")

        folder.resolve("second.py").writeText("")
        assertEquals(listOf(ScriptState.RUNNING), scripts.list().map { it.state }, "a running script stays listed")
        assertEquals(ScriptState.INACTIVE, scripts.stop(created.id).state, "a user's stop is no failure")
        assertEquals(emptyList(), scripts.list(), "a folder with two code files is no script")
        folder.resolve("second.py").deleteExisting()

        scripts.createResponse("bad/title").assertRejected("invalid_request", "a title that cannot name a file")
        assertEquals(failed.logPath, scripts.rename(created.id, "Renamed").logPath, "the log keeps its name")
        scripts.rename(created.id, "renamed")
        val codeFiles = folder.listDirectoryEntries("*.py").map { it.name }
        assertEquals(listOf("renamed.py"), codeFiles, "a rename moves the file, in letter case alone too")
        folder.resolve("renamed.py").moveTo(folder.resolve("Moved by hand.py"))
        assertEquals("Moved by hand", scripts.list().single().title, "the code file's name is the title")

        val elsewhere = ScriptClient(http, localWorkspace(tempDir, "elsewhere"))
        assertEquals(emptyList(), elsewhere.list(), "a script is listed in its own workspace only")
        elsewhere.startResponse(created.id)
            .assertRejected("unknown_script", "another workspace's script", status = HttpStatusCode.NotFound)

        assertEquals(HttpStatusCode.NoContent, scripts.deleteResponse(created.id).status)
        assertFalse(folder.exists(), "delete removes the script's folder")
        scripts.stopResponse(created.id)
            .assertRejected("unknown_script", "a deleted script", status = HttpStatusCode.NotFound)
    }

    @Test
    @DisplayName("A killed server takes its scripts and their children with it, and the next server starts none")
    fun aKilledServerLeavesNoScriptProcess() {
        val dataDir = tempDir.resolve("data")
        val workspace = localWorkspace(tempDir)
        val pidsFile = tempDir.resolve("pids.txt")

        val scriptId = LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                runBlocking {
                    val scripts = ScriptClient(http, workspace)
                    val script = scripts.create("Orphan")
                    Path.of(script.codePath).writeText(
                        """
                        import os, subprocess, time
                        child = subprocess.Popen(["sleep", "600"])
                        open("$pidsFile", "w").write(f"{os.getpid()} {child.pid}")
                        time.sleep(600)
                        """.trimIndent(),
                    )
                    scripts.start(script.id)
                    awaited("the script reporting its pids") { pidsFile.takeIf { it.exists() && it.readText() != "" } }
                    server.crash()
                    script.id
                }
            }
        }

        val pids = pidsFile.readText().split(" ").map { it.toLong() }
        runBlocking {
            awaited("the script and its child dying with the server") {
                Unit.takeIf { pids.none { ProcessHandle.of(it).isPresent } }
            }
        }
        LiveServerProcess.start(dataDir).use { server ->
            liveHttpClient(server.baseUrl).use { http ->
                val listed = runBlocking { ScriptClient(http, workspace).list() }
                assertEquals(listOf(scriptId to ScriptState.INACTIVE), listed.map { it.id to it.state })
            }
        }
    }
}

private fun focusOnceScript(harness: String): String =
    """
    import time
    from pathlib import Path

    import momo

    planned = Path("planned.txt")
    if not planned.exists():
        momo.create_session(
            title="Focus today",
            harness="$harness",
            model="${liveSettings.model}",
            reasoning_effort="low",
            prompt=momo.template("daily-focus").replace("{{date}}", "today"),
        )
        planned.write_text("done")
    time.sleep(600)
    """.trimIndent()
