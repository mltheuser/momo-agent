package codes.momo.agent.environment

import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun interface PrivilegeProbe {

    fun run(command: List<String>): ExecResult
}

internal fun hostPrivilegeProbe(workspace: Path): PrivilegeProbe = PrivilegeProbe { command ->
    runProcessBlocking(command, workingDirectory = workspace, timeout = PROBE_TIMEOUT)
}

internal fun PrivilegeProbe.detect(): Privilege = when {
    grants(EUID_PROBE) -> Privilege.ROOT
    grants(SUDO_PROBE) -> Privilege.PASSWORDLESS_SUDO
    else -> Privilege.UNPRIVILEGED
}

private fun PrivilegeProbe.grants(command: List<String>): Boolean =
    try {
        run(command).succeeded
    } catch (_: IOException) {
        false
    }

private val EUID_PROBE = listOf("bash", "-c", "[ \"\$EUID\" -eq 0 ]")

private val SUDO_PROBE = listOf("sudo", "-n", "-k", "true") // -k: a warm credential cache must not pass for NOPASSWD.

private val PROBE_TIMEOUT: Duration = 10.seconds
