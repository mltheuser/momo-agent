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
        timeout: Duration = 30.seconds,
        workspace: Path = tempDir,
    ): ExecResult = runBlocking {
        LocalExecutionEnvironment(workspace).exec(command.toList(), timeout)
    }

    private fun ExecResult.assertCompletedOk(): ExecResult.Completed {
        val completed = assertIs<ExecResult.Completed>(this)
        assertEquals(0, completed.exitCode)
        return completed
    }

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
    @DisplayName("stdin is closed immediately: a stdin-reading command sees EOF instead of hanging")
    fun stdinIsImmediateEof() {
        val result = exec("cat", timeout = 5.seconds)

        assertEquals("", result.assertCompletedOk().stdout)
    }

    // ─── Output capture ───────────────────────────────────────────────

    @Test
    @DisplayName("A 1 MiB stdout (bigger than a pipe buffer) is drained without deadlock")
    fun largeStdoutDoesNotDeadlock() {
        val result = exec("head", "-c", "1048576", "/dev/zero")

        // NUL bytes decode to one char each, so String.length counts bytes.
        assertEquals(1 shl 20, result.assertCompletedOk().stdout.length)
    }

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

    // ─── Workspace path ───────────────────────────────────────────────

    @Test
    @DisplayName("workspacePath reports the workspace as a normalized absolute host path")
    fun workspacePathIsTheAbsoluteWorkspace() {
        // A messy-but-valid spelling of the same directory must come back
        // as the canonical absolute path.
        val environment = LocalExecutionEnvironment(tempDir.resolve("."))

        assertEquals(tempDir.toString(), environment.workspacePath)
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
            LocalExecutionEnvironment(tempDir, Privilege.UNPRIVILEGED, searchPath = stubBin.toString())
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
            LocalExecutionEnvironment(tempDir, Privilege.UNPRIVILEGED, searchPath = null)
        }
        val message = exception.message.orEmpty()
        BASELINE_BINARIES.forEach { binary ->
            assertContains(message, binary, message = "expected '$binary' to be named, was: $message")
        }
    }

    // ─── Privilege verification ───────────────────────────────────────
    //
    // The host's own posture is deliberately out of play here: every claim is
    // falsified against an injected probe, so the suite passes as root, as a
    // NOPASSWD-granted CI user and on a host without `sudo` alike.

    /** Constructs over [tempDir] claiming [privilege], with [probe] answering for the host. */
    private fun environment(privilege: Privilege, probe: PrivilegeProbe): LocalExecutionEnvironment =
        LocalExecutionEnvironment(tempDir, privilege, System.getenv("PATH"), probe)

    /** A probe that appends every command it is asked to run to [commands] and confirms the claim. */
    private fun recordingProbe(commands: MutableList<List<String>>): PrivilegeProbe = PrivilegeProbe { command ->
        commands += command
        completed()
    }

    @Test
    @DisplayName("An unprivileged claim is taken at its word: nothing is probed and the environment reports it")
    fun unprivilegedClaimIsNotProbed() {
        val probed = mutableListOf<List<String>>()

        val environment = environment(Privilege.UNPRIVILEGED, recordingProbe(probed))

        assertEquals(Privilege.UNPRIVILEGED, environment.privilege)
        assertTrue(probed.isEmpty(), "an unprivileged claim must not be probed, ran: $probed")
    }

    @Test
    @DisplayName("Each probed claim runs the one command that can falsify it")
    fun eachClaimRunsItsOwnProbe() {
        val probed = mutableListOf<List<String>>()

        environment(Privilege.ROOT, recordingProbe(probed))
        environment(Privilege.PASSWORDLESS_SUDO, recordingProbe(probed))

        // The argv is the contract with the host, so it is pinned verbatim,
        // `-k` included (the probe's KDoc says why it matters).
        assertEquals(
            listOf(
                listOf("bash", "-c", "echo \"\$EUID\"; [ \"\$EUID\" -eq 0 ]"),
                listOf("sudo", "-n", "-k", "true"),
            ),
            probed,
        )
    }

    @Test
    @DisplayName("A claim the probe confirms constructs successfully and is reported as declared")
    fun confirmedClaimSucceeds() {
        listOf(Privilege.ROOT, Privilege.PASSWORDLESS_SUDO).forEach { claimed ->
            val environment = environment(claimed) { completed() }

            assertEquals(claimed, environment.privilege)
        }
    }

    @Test
    @DisplayName("A root claim fails when the probe reports a non-root effective uid, naming that uid")
    fun rootClaimWithNonRootEuidFails() {
        val exception = assertFailsWith<EnvironmentStartupException> {
            environment(Privilege.ROOT) { completed(exitCode = 1, stdout = "501\n") }
        }
        val message = exception.message.orEmpty()
        assertContains(message, ROOT_DECLARED)
        assertContains(message, "501")
    }

    @Test
    @DisplayName("A passwordless-sudo claim the probe is denied fails, naming the sudoers remedy")
    fun deniedPasswordlessSudoClaimFails() {
        val exception = assertFailsWith<EnvironmentStartupException> {
            environment(Privilege.PASSWORDLESS_SUDO) {
                completed(exitCode = 1, stderr = "sudo: a password is required")
            }
        }
        val message = exception.message.orEmpty()
        assertContains(message, SUDO_DECLARED)
        assertContains(message, "sudo -n -k true")
        assertContains(message, "a password is required")
        assertContains(message, "NOPASSWD")
        // The message goes out verbatim as the API's 400 body, where the wire
        // vocabulary is lowercase, so the remedy must not name an enum constant.
        assertFalse(
            Privilege.UNPRIVILEGED.name in message,
            "the remedy must read for an HTTP client too: $message",
        )
    }

    @Test
    @DisplayName("A probe program that cannot be started fails construction instead of escaping as an IOException")
    fun unstartableProbeProgramFailsConstruction() {
        mapOf(Privilege.ROOT to ROOT_DECLARED, Privilege.PASSWORDLESS_SUDO to SUDO_DECLARED)
            .forEach { (claimed, declared) ->
                val exception = assertFailsWith<EnvironmentStartupException> {
                    environment(claimed) { throw IOException("Cannot run program") }
                }

                assertContains(exception.message.orEmpty(), declared)
                assertIs<IOException>(exception.cause)
            }
    }

    @Test
    @DisplayName("An incomplete userland baseline fails before anything is probed, so a probe may rely on bash")
    fun baselineIsCheckedBeforeProbing() {
        val probed = mutableListOf<List<String>>()

        assertFailsWith<EnvironmentStartupException> {
            LocalExecutionEnvironment(
                tempDir,
                privilege = Privilege.ROOT,
                searchPath = null,
                probe = recordingProbe(probed),
            )
        }

        assertTrue(probed.isEmpty(), "the baseline scan must fail first, ran: $probed")
    }

    @Test
    @DisplayName("The real probe runs in the workspace, like every other command of this environment")
    fun hostProbeRunsInTheWorkspace() {
        val workspace = tempDir.resolve("workspace").createDirectories()

        val result = hostPrivilegeProbe(workspace).run(listOf("bash", "-c", "pwd"))

        // Compare real paths: temp dirs can live behind symlinks (e.g. /tmp on macOS).
        assertEquals(workspace.toRealPath().toString(), result.assertCompletedOk().stdout.trim())
    }

    private companion object {

        /**
         * The baseline the environment must require — deliberately duplicated
         * from production so a change to the declared invariant fails a test.
         */
        val BASELINE_BINARIES = listOf("bash", "cat", "cp", "find", "grep", "ls", "mkdir", "mv", "rm", "sed")

        /** How long the polling helpers wait for a killed process / pid file before failing. */
        val KILL_GRACE_PERIOD = 5.seconds

        /** How a denial names the claim it falsified — prose, so it reads on the wire too. */
        const val ROOT_DECLARED = "Root privilege was declared"
        const val SUDO_DECLARED = "Passwordless sudo was declared"
    }
}
