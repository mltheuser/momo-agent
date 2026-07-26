package codes.momo.agent.environment

import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a privilege probe reaches the host: one command in, its outcome out. A
 * seam like [ProcessKiller] — the public constructors run the real host's
 * programs, while a test can answer any posture without having one.
 */
internal fun interface PrivilegeProbe {

    /** @throws IOException when the probe program cannot be started at all. */
    fun run(command: List<String>): ExecResult
}

/**
 * The real probe. Runs in [workspace] like every other command of that
 * environment, so a probe passes or fails under the conditions of the
 * commands it vouches for.
 */
internal fun hostPrivilegeProbe(workspace: Path): PrivilegeProbe = PrivilegeProbe { command ->
    runProcessBlocking(command, workingDirectory = workspace, timeout = PROBE_TIMEOUT)
}

/**
 * Falsifies the [claimed] privilege against the host. Call once the userland
 * baseline is established — that is what lets a probe use `bash` rather than,
 * say, `id -u`.
 *
 * @throws EnvironmentStartupException when the host does not grant [claimed],
 *   naming what it reported instead and the remedy.
 */
internal fun PrivilegeProbe.verify(claimed: Privilege) {
    when (claimed) {
        // Taken at its word, unprobed: under-claiming is safe, and a
        // deployment that happens to run as root must keep working.
        Privilege.UNPRIVILEGED -> Unit
        Privilege.ROOT -> verifyRoot()
        Privilege.PASSWORDLESS_SUDO -> verifyPasswordlessSudo()
    }
}

private fun PrivilegeProbe.verifyRoot() {
    val result = runOrDeny(listOf("bash", "-c", EUID_PROBE_SCRIPT), ::rootDenied)
    if (result.succeeded) {
        return
    }
    val euid = (result as? ExecResult.Completed)?.stdout?.trim().orEmpty()
    val detail = if (euid.isEmpty()) result.problem() else "the effective uid is $euid"
    throw EnvironmentStartupException(rootDenied(detail))
}

/**
 * `-k` is what makes this precise: a warm credential cache would otherwise
 * pass for a NOPASSWD entry and then decay minutes into a run budgeted in
 * days. With a command given, `-k` ignores the cache without invalidating it,
 * so the probe leaves no trace.
 */
private fun PrivilegeProbe.verifyPasswordlessSudo() {
    val result = runOrDeny(listOf("sudo", "-n", "-k", "true"), ::sudoDenied)
    if (!result.succeeded) {
        throw EnvironmentStartupException(sudoDenied("`sudo -n -k true` failed: ${result.problem()}"))
    }
}

/**
 * Turns a probe program that cannot be started at all — absent, or unrunnable
 * despite being on `PATH` — into the [denied] refusal it amounts to.
 */
private fun PrivilegeProbe.runOrDeny(command: List<String>, denied: (String) -> String): ExecResult =
    try {
        run(command)
    } catch (unstartable: IOException) {
        throw EnvironmentStartupException(denied("`${command.first()}` could not be started"), unstartable)
    }

/** Puts the effective uid on stdout so a denial can name it. */
private const val EUID_PROBE_SCRIPT = "echo \"\$EUID\"; [ \"\$EUID\" -eq 0 ]"

/**
 * Deadline for a privilege probe. Generous for a local `true`, tight enough
 * that a stalled `sudo -n` — a PAM or LDAP lookup, which is not passwordless
 * in any useful sense — fails instead of hanging.
 */
private val PROBE_TIMEOUT: Duration = 10.seconds

private fun rootDenied(detail: String): String =
    "Root privilege was declared, but commands do not run as root — $detail. Run this process as " +
        "root, or declare the privilege it actually has."

private fun sudoDenied(detail: String): String =
    "Passwordless sudo was declared, but $detail. Grant a NOPASSWD sudoers entry for the user this " +
        "process runs as (see the README's platform section), or declare that commands run unprivileged."
