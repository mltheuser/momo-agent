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
 * environment, so a probe reports the posture of the commands it speaks for.
 */
internal fun hostPrivilegeProbe(workspace: Path): PrivilegeProbe = PrivilegeProbe { command ->
    runProcessBlocking(command, workingDirectory = workspace, timeout = PROBE_TIMEOUT)
}

/**
 * Derives the posture the host actually grants. Nothing is declared and so
 * nothing can be wrong: a claim could only ever narrow what the model was
 * told while the host went on granting what it granted, which is a
 * confusing falsehood rather than a restraint — the elevation a command can
 * reach is a property of the account this process runs as, not of anything
 * expressible here.
 *
 * Ordered most-privileged first, because the postures overlap: root can
 * satisfy the sudo probe too, and would be described by the wrong guidance
 * if that answered first.
 *
 * Call once the userland baseline is established — that is what lets a probe
 * use `bash` rather than, say, `id -u`.
 */
internal fun PrivilegeProbe.detect(): Privilege = when {
    grants(EUID_PROBE) -> Privilege.ROOT
    grants(SUDO_PROBE) -> Privilege.PASSWORDLESS_SUDO
    else -> Privilege.UNPRIVILEGED
}

/**
 * Whether [command] ran and reported success. A probe program that cannot be
 * started at all — no `sudo` on the host, say — is the plainest possible
 * denial of the posture it tests, not a failure: detection always has a
 * floor to fall back to, so it never throws.
 */
private fun PrivilegeProbe.grants(command: List<String>): Boolean =
    try {
        run(command).succeeded
    } catch (_: IOException) {
        false
    }

/** Succeeds exactly when commands already run as root. */
private val EUID_PROBE = listOf("bash", "-c", "[ \"\$EUID\" -eq 0 ]")

/**
 * `-k` is what makes this precise: a warm credential cache would otherwise
 * pass for a NOPASSWD entry and then decay minutes into a run budgeted in
 * days. With a command given, `-k` ignores the cache without invalidating it,
 * so the probe leaves no trace.
 */
private val SUDO_PROBE = listOf("sudo", "-n", "-k", "true")

/**
 * Deadline for a privilege probe. Generous for a local `true`, tight enough
 * that a stalled `sudo -n` — a PAM or LDAP lookup, which is not passwordless
 * in any useful sense — resolves to unprivileged instead of hanging.
 */
private val PROBE_TIMEOUT: Duration = 10.seconds
