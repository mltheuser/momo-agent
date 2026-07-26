package codes.momo.agent.environment

import codes.momo.agent.labeledContainers
import codes.momo.agent.tool.BashArgs
import codes.momo.agent.tool.BashTool
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ContainerExecutionEnvironmentTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Runs [block] against a fresh environment over [workspace], closing it afterwards. */
    private fun <T> withEnvironment(
        image: String = DEBIAN_IMAGE,
        workspace: Path = tempDir,
        block: suspend (ContainerExecutionEnvironment) -> T,
    ): T = runBlocking {
        ContainerExecutionEnvironment(image, workspace).use { block(it) }
    }

    private suspend fun ContainerExecutionEnvironment.bash(
        script: String,
        timeout: Duration = 30.seconds,
    ): ExecResult = exec(listOf("bash", "-c", script), timeout = timeout)

    private fun ExecResult.assertCompletedOk(): ExecResult.Completed {
        val completed = assertIs<ExecResult.Completed>(this)
        assertEquals(0, completed.exitCode, "command failed: ${completed.stderr}")
        return completed
    }

    /** Asserts the tool call succeeded with exit code 0 — Success alone also covers failing commands. */
    private fun ToolResult.assertExitZero(): String {
        val resultText = assertIs<ToolResult.Success>(this, text).text
        assertTrue(resultText.startsWith("exit code: 0\n"), "command failed: $resultText")
        return resultText
    }

    // ─── Core tool scenarios ──────────────────────────────────────────

    @Test
    @DisplayName("The bash tool runs in the container with /workspace as the working directory")
    fun bashToolRunsInContainerWorkspace() {
        withEnvironment { environment ->
            assertEquals("/workspace", environment.workspacePath)

            val bash = BashTool(environment.workspacePath)
            val result = bash.execute(BashArgs("pwd && cat /etc/os-release"), environment)

            val text = assertIs<ToolResult.Success>(result, result.text).text
            assertContains(text, "/workspace")
            assertContains(text, "Debian GNU/Linux 12")
        }
    }

    @Test
    @DisplayName("The bash tool writes, reads back, and edits tricky content in the container")
    fun bashToolRoundTripsTrickyContent() {
        val tricky = "it's \"double\" `backtick` \$(sub) \${brace} \\slash\nsecond line\n"
        withEnvironment { environment ->
            val bash = BashTool(environment.workspacePath)
            // A quoted heredoc delimiter keeps every metacharacter literal.
            bash.execute(
                BashArgs("cat > tricky.txt <<'MOMO_EOF'\n${tricky}MOMO_EOF"),
                environment,
            ).assertExitZero()

            val read = bash.execute(BashArgs("cat tricky.txt"), environment)
            assertContains(
                read.assertExitZero(),
                tricky,
                message = "the bash tool did not return the written content verbatim: ${read.text}",
            )

            bash.execute(BashArgs("sed -i 's/`backtick`/[EDITED]/' tricky.txt"), environment).assertExitZero()

            val readBack = environment.exec(listOf("cat", "/workspace/tricky.txt"), timeout = 30.seconds)
            assertEquals(tricky.replace("`backtick`", "[EDITED]"), readBack.assertCompletedOk().stdout)
        }
    }

    @Test
    @DisplayName("Content without a trailing newline survives the write byte-exact")
    fun bashToolWritesNoTrailingNewlineByteExact() {
        val bare = "it's \"double\" `backtick` \$(sub) \${brace} \\slash ends bare"
        withEnvironment { environment ->
            val bash = BashTool(environment.workspacePath)
            // The heredoc adds a final newline; truncate drops it again.
            bash.execute(
                BashArgs("cat > bare.txt <<'MOMO_EOF'\n${bare}\nMOMO_EOF\ntruncate -s -1 bare.txt"),
                environment,
            ).assertExitZero()

            val readBack = environment.exec(listOf("cat", "/workspace/bare.txt"), timeout = 30.seconds)
            assertEquals(bare, readBack.assertCompletedOk().stdout)
        }
    }

    // ─── stdin ────────────────────────────────────────────────────────

    @Test
    @DisplayName("stdin is closed immediately: a stdin-reading command sees EOF instead of hanging")
    fun stdinIsImmediateEof() {
        withEnvironment { environment ->
            val result = environment.exec(listOf("cat"), timeout = 10.seconds)

            assertEquals("", result.assertCompletedOk().stdout)
        }
    }

    // ─── Image pinning ────────────────────────────────────────────────

    @Test
    @DisplayName("A node:12 container reports a v12 runtime — the image pin is the time machine")
    fun pinnedImageProvidesItsRuntime() {
        withEnvironment(image = NODE_IMAGE) { environment ->
            val result = environment.exec(listOf("node", "--version"), timeout = 30.seconds)

            val version = result.assertCompletedOk().stdout.trim()
            assertTrue(version.startsWith("v12."), "expected a node 12 runtime, got: $version")
        }
    }

    // ─── Timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A timeout returns TimedOut with pre-kill output and no surviving in-container process")
    fun timeoutKillsInContainerProcesses() {
        withEnvironment { environment ->
            val result = environment.bash("sleep 31607 & echo started; sleep 31607", timeout = 2.seconds)

            val timedOut = assertIs<ExecResult.TimedOut>(result)
            assertEquals("started\n", timedOut.stdout, "pre-kill output must be preserved")

            // `3160[7]` keeps the probe's own cmdline from matching itself.
            val probe = environment.bash("grep -asl '3160[7]' /proc/[0-9]*/cmdline || echo none")
            assertEquals(
                "none\n",
                probe.assertCompletedOk().stdout,
                "in-container processes survived the timeout kill",
            )
        }
    }

    // ─── Teardown & leaks ─────────────────────────────────────────────

    @Test
    @DisplayName("close removes the container and a second close is a no-op")
    fun closeRemovesContainerAndIsIdempotent() {
        val before = labeledContainers()
        val environment = ContainerExecutionEnvironment(DEBIAN_IMAGE, tempDir)
        val created = labeledContainers() - before
        assertEquals(1, created.size, "expected exactly one new labeled container, got: $created")

        environment.close()

        assertEquals(before, labeledContainers(), "container ${created.single()} was not removed by close()")
        environment.close()
        assertEquals(before, labeledContainers())
    }

    @Test
    @DisplayName("An image without bash fails startup naming the userland baseline, leaving no container behind")
    fun imageWithoutBaselineFailsStartup() {
        val before = labeledContainers()

        val exception = assertFailsWith<EnvironmentStartupException> {
            ContainerExecutionEnvironment(BASH_LESS_IMAGE, tempDir)
        }

        assertContains(exception.message.orEmpty(), "userland baseline")
        assertEquals(before, labeledContainers(), "a failed startup must not leave a container behind")
    }

    @Test
    @DisplayName("A container dying mid-run surfaces as EnvironmentFailureException, not as a command result")
    fun deadContainerSurfacesAsEnvironmentFailure() {
        val before = labeledContainers()
        val environment = ContainerExecutionEnvironment(DEBIAN_IMAGE, tempDir)
        val container = (labeledContainers() - before).single()
        runBlocking {
            runProcess(listOf("docker", "rm", "-f", container), timeout = 30.seconds).assertCompletedOk()

            val exception = assertFailsWith<EnvironmentFailureException> { environment.bash("echo alive") }

            assertContains(exception.message.orEmpty(), container)
        }
        // Closing afterwards reports the lost workspace but still cleans up.
        assertFailsWith<EnvironmentFailureException> { environment.close() }
        assertEquals(before, labeledContainers())
    }

    @Test
    @DisplayName("A nonexistent image tag fails startup with a clear error and leaves no container behind")
    fun failedStartupDoesNotLeak() {
        val image = "debian:momo-agent-no-such-tag"
        val before = labeledContainers()

        val exception = assertFailsWith<EnvironmentStartupException> {
            ContainerExecutionEnvironment(image, tempDir)
        }

        assertContains(exception.message.orEmpty(), image)
        assertEquals(before, labeledContainers(), "a failed startup must not leave a container behind")
    }

    // ─── Workspace transfer ───────────────────────────────────────────

    @Test
    @DisplayName("Workspace contents — dotfiles included — are copied in, and results copy back out on close")
    fun workspaceCopiesInAndOut() {
        tempDir.resolve(".hidden").writeText("dotfile survives\n")
        val environment = ContainerExecutionEnvironment(DEBIAN_IMAGE, tempDir)
        try {
            runBlocking {
                val dotfile = environment.exec(listOf("cat", ".hidden"), timeout = 30.seconds)
                assertEquals("dotfile survives\n", dotfile.assertCompletedOk().stdout)

                environment.bash("echo produced-in-container > result.txt").assertCompletedOk()
            }
        } finally {
            environment.close()
        }
        assertEquals("produced-in-container\n", tempDir.resolve("result.txt").readText())
    }

    // ─── Concurrency ──────────────────────────────────────────────────

    @Test
    @DisplayName("Two environments run at once without interference and both close clean")
    fun twoEnvironmentsRunWithoutInterference() {
        val workspaceA = tempDir.resolve("workspace-a").createDirectories()
        val workspaceB = tempDir.resolve("workspace-b").createDirectories()

        runBlocking {
            ContainerExecutionEnvironment(DEBIAN_IMAGE, workspaceA).use { environmentA ->
                ContainerExecutionEnvironment(DEBIAN_IMAGE, workspaceB).use { environmentB ->
                    val writes = listOf(
                        async { environmentA.bash("echo from-a > a.txt") },
                        async { environmentB.bash("echo from-b > b.txt") },
                    )
                    writes.forEach { it.await().assertCompletedOk() }
                    assertEquals("a.txt\n", environmentA.bash("ls").assertCompletedOk().stdout)
                    assertEquals("b.txt\n", environmentB.bash("ls").assertCompletedOk().stdout)
                }
            }
        }

        assertEquals("from-a\n", workspaceA.resolve("a.txt").readText())
        assertEquals("from-b\n", workspaceB.resolve("b.txt").readText())
        assertFalse(workspaceA.resolve("b.txt").exists(), "workspaces must not bleed into each other")
    }

    private companion object {

        /** Small pinned image for the general scenarios. */
        const val DEBIAN_IMAGE = "debian:12-slim"

        /** Pinned old runtime proving image selection is the time machine. */
        const val NODE_IMAGE = "node:12"

        /** Pinned image without bash, for the userland-baseline failure scenario. */
        const val BASH_LESS_IMAGE = "alpine:3.20"
    }
}
