package codes.momo.agent.environment

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class LocalExecutionEnvironmentTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun environment(): LocalExecutionEnvironment = LocalExecutionEnvironment(tempDir)

    /** Runs [command] in a fresh environment over [workspace]. */
    private fun exec(
        vararg command: String,
        stdin: ByteArray? = null,
        timeout: Duration = 30.seconds,
        workspace: Path = tempDir,
    ): ExecResult = runBlocking {
        LocalExecutionEnvironment(workspace).exec(command.toList(), stdin, timeout)
    }

    private fun ExecResult.assertCompletedOk(): ExecResult.Completed {
        val completed = assertIs<ExecResult.Completed>(this)
        assertEquals(0, completed.exitCode)
        return completed
    }

    private fun hexOf(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    /** Waits (up to [KILL_GRACE_PERIOD]) for the process with [pid] to be gone. */
    private fun assertProcessDies(pid: Long) {
        val deadline = System.nanoTime() + KILL_GRACE_PERIOD.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            val handle = ProcessHandle.of(pid)
            if (handle.isEmpty || !handle.get().isAlive) {
                return
            }
            Thread.sleep(50)
        }
        fail("process $pid is still alive after the kill grace period")
    }

    // ─── Completion: success and failure ──────────────────────────────

    @Test
    @DisplayName("A successful command completes with exit 0 and captured stdout")
    fun successCapturesStdout() {
        val result = exec("bash", "-c", "echo hello")

        val completed = result.assertCompletedOk()
        assertEquals("hello\n", completed.stdout)
        assertEquals("", completed.stderr)
        assertFalse(completed.stdoutTruncated)
        assertFalse(completed.stderrTruncated)
    }

    @Test
    @DisplayName("A failing command is a Completed with non-zero exit and stderr — not a timeout")
    fun failureIsCompletedNotTimedOut() {
        val result = exec("bash", "-c", "echo oops >&2; exit 3")

        val completed = assertIs<ExecResult.Completed>(result)
        assertEquals(3, completed.exitCode)
        assertEquals("oops\n", completed.stderr)
        assertEquals("", completed.stdout)
    }

    // ─── Caller errors ────────────────────────────────────────────────

    @Test
    @DisplayName("An unstartable executable propagates IOException — a caller error, not a command outcome")
    fun unstartableExecutablePropagatesIOException() {
        assertFailsWith<IOException> {
            exec("definitely-not-a-real-binary-xyz")
        }
    }

    @Test
    @DisplayName("An empty command list is rejected with IllegalArgumentException")
    fun emptyCommandListIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            exec()
        }
    }

    // ─── Timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A timeout is the TimedOut case, kills the whole process tree, and carries pre-kill output")
    fun timeoutKillsProcessTreeAndCarriesPartialOutput() {
        // The background sleep is a child of bash; its pid lands on stdout.
        val result = exec(
            "bash",
            "-c",
            "sleep 30 & echo \$!; echo early err >&2; sleep 30",
            timeout = 2.seconds,
        )

        val timedOut = assertIs<ExecResult.TimedOut>(result)
        assertEquals("early err\n", timedOut.stderr)
        assertTrue(
            timedOut.stdout.isNotBlank(),
            "bash did not echo the background pid before the timeout kill — machine too loaded?",
        )
        assertProcessDies(timedOut.stdout.trim().toLong())
    }

    // ─── stdin ────────────────────────────────────────────────────────

    @Test
    @DisplayName("stdin text with quotes, newlines, and shell metacharacters passes through exactly")
    fun stdinTrickyTextPassesThroughExactly() {
        val tricky = "line one\n\"double\" 'single' `backtick`\n\$HOME && rm -rf | ; \\ *?[a-z] é\nno trailing newline"

        val result = exec("cat", stdin = tricky.toByteArray())

        assertEquals(tricky, result.assertCompletedOk().stdout)
    }

    @Test
    @DisplayName("stdin is binary-safe: a 1 MiB payload cycling all 256 byte values round-trips exactly")
    fun stdinIsBinarySafe() {
        val bytes = ByteArray(1 shl 20) { it.toByte() }

        // od (POSIX) hex-dumps the bytes back — raw ones would be mangled by
        // the result's UTF-8 decoding. The payload also spans many pipe
        // buffers, so a writer deadlock would surface here as a timeout.
        val result = exec("od", "-An", "-v", "-tx1", stdin = bytes)

        val completed = result.assertCompletedOk()
        assertTrue(
            completed.stdout.filterNot { it.isWhitespace() } == hexOf(bytes),
            "1 MiB stdin payload did not round-trip byte-exactly",
        )
    }

    @Test
    @DisplayName("A 1 MiB stdout (bigger than a pipe buffer) is drained without deadlock")
    fun largeStdoutDoesNotDeadlock() {
        val result = exec("head", "-c", "1048576", "/dev/zero")

        // NUL bytes decode to one char each, so String.length counts bytes.
        assertEquals(1 shl 20, result.assertCompletedOk().stdout.length)
    }

    @Test
    @DisplayName("A process that exits without reading its stdin does not crash the exec")
    fun processExitingWithoutReadingStdinDoesNotCrash() {
        // Large enough that the writer is still blocked on a full pipe buffer
        // when the process dies — the resulting broken pipe must be swallowed.
        val unread = ByteArray(1 shl 20)

        val result = exec("bash", "-c", "exit 7", stdin = unread)

        val completed = assertIs<ExecResult.Completed>(result)
        assertEquals(7, completed.exitCode)
    }

    // ─── Output capture cap ───────────────────────────────────────────

    @Test
    @DisplayName("stdout past the capture cap is discarded, flagged, and the process still completes")
    fun oversizedStdoutIsCappedAndFlagged() {
        // 9 MB > the 8 MiB cap. The trailing echo proves the pipe kept being
        // drained past the cap and the command ran to the end.
        val result = exec("bash", "-c", "head -c 9000000 /dev/zero && echo ran >&2")

        val completed = result.assertCompletedOk()
        // NUL bytes decode to one char each, so String.length counts bytes.
        assertEquals(ExecutionEnvironment.MAX_CAPTURED_BYTES, completed.stdout.length)
        assertTrue(completed.stdoutTruncated)
        assertEquals("ran\n", completed.stderr)
        assertFalse(completed.stderrTruncated)
    }

    @Test
    @DisplayName("stderr past the capture cap is discarded and flagged independently of stdout")
    fun oversizedStderrIsCappedAndFlagged() {
        val result = exec("bash", "-c", "head -c 9000000 /dev/zero >&2 && echo ran")

        val completed = result.assertCompletedOk()
        assertEquals(ExecutionEnvironment.MAX_CAPTURED_BYTES, completed.stderr.length)
        assertTrue(completed.stderrTruncated)
        assertEquals("ran\n", completed.stdout)
        assertFalse(completed.stdoutTruncated)
    }

    // ─── Working directory ────────────────────────────────────────────

    @Test
    @DisplayName("Commands run with the workspace as their working directory")
    fun commandsRunInWorkspace() {
        val workspace = tempDir.resolve("workspace").createDirectories()

        val result = exec("pwd", workspace = workspace)

        // Compare real paths: temp dirs can live behind symlinks (e.g. /tmp on macOS).
        assertEquals(workspace.toRealPath().toString(), result.assertCompletedOk().stdout.trim())
    }

    // ─── Cancellation ─────────────────────────────────────────────────

    @Test
    @DisplayName("Coroutine cancellation kills the process tree like a timeout does")
    fun cancellationKillsProcessTree() {
        val pidFile = tempDir.resolve("child.pid")
        runBlocking {
            val job = launch {
                environment().exec(
                    listOf("bash", "-c", "sleep 30 & echo \$! > child.pid; sleep 30"),
                    timeout = 30.seconds,
                )
            }
            val backgroundPid = waitForPidFile(pidFile)
            job.cancelAndJoin()
            assertProcessDies(backgroundPid)
        }
    }

    /**
     * Polls until the command under test has written its background child's
     * pid. Suspends between polls: runBlocking's event loop is single-
     * threaded, so a blocking sleep here would starve the exec coroutine.
     */
    private suspend fun waitForPidFile(pidFile: Path): Long {
        val deadline = System.nanoTime() + KILL_GRACE_PERIOD.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (pidFile.exists()) {
                val text = pidFile.readText().trim()
                if (text.isNotEmpty()) {
                    return text.toLong()
                }
            }
            delay(20)
        }
        fail("pid file was not written in time: $pidFile")
    }

    // ─── Startup validation ───────────────────────────────────────────

    @Test
    @DisplayName("A missing workspace fails construction, naming the path")
    fun missingWorkspaceFails() {
        val missing = tempDir.resolve("does-not-exist")

        val exception = assertFailsWith<EnvironmentStartupException> {
            LocalExecutionEnvironment(missing)
        }
        assertContains(exception.message.orEmpty(), missing.toString())
    }

    @Test
    @DisplayName("A workspace path that is a file, not a directory, fails construction")
    fun fileWorkspaceFails() {
        val file = tempDir.resolve("workspace-as-file")
        file.writeText("not a folder")

        val exception = assertFailsWith<EnvironmentStartupException> {
            LocalExecutionEnvironment(file)
        }
        assertContains(exception.message.orEmpty(), file.toString())
    }

    @Test
    @DisplayName("Missing host binaries fail construction, naming every missing one")
    fun missingBinariesAreAllNamed() {
        // A restricted PATH with stubs for everything except grep and find.
        val stubBin = tempDir.resolve("stub-bin").createDirectories()
        BASELINE_BINARIES
            .filterNot { it == "grep" || it == "find" }
            .forEach { name ->
                val stub = stubBin.resolve(name)
                stub.writeText("#!/bin/bash\n")
                stub.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            }

        val exception = assertFailsWith<EnvironmentStartupException> {
            LocalExecutionEnvironment(tempDir, searchPath = stubBin.toString())
        }
        val message = exception.message.orEmpty()
        assertContains(message, "grep")
        assertContains(message, "find")
        assertFalse("bash" in message, "present binaries must not be reported: $message")
    }

    @Test
    @DisplayName("With no PATH at all, the full baseline is named")
    fun noSearchPathNamesTheFullBaseline() {
        val exception = assertFailsWith<EnvironmentStartupException> {
            LocalExecutionEnvironment(tempDir, searchPath = null)
        }
        val message = exception.message.orEmpty()
        BASELINE_BINARIES.forEach { binary ->
            assertContains(message, binary, message = "expected '$binary' to be named, was: $message")
        }
    }

    private companion object {

        /**
         * The baseline the environment must require — deliberately duplicated
         * from production so a change to the declared invariant fails a test.
         */
        val BASELINE_BINARIES = listOf("bash", "cat", "cp", "find", "grep", "ls", "mkdir", "mv", "rm", "sed")

        /** How long the polling helpers wait for a killed process / pid file before failing. */
        val KILL_GRACE_PERIOD = 5.seconds
    }
}
