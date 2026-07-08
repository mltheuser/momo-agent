package codes.momo.agent

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.runProcess
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/** Names of momo-agent containers currently known to docker, found via the cleanup label. */
public fun labeledContainers(): Set<String> = runBlocking {
    val listing = runProcess(
        listOf("docker", "ps", "-a", "--filter", "label=codes.momo.agent", "--format", "{{.Names}}"),
        timeout = 30.seconds,
    )
    check(listing is ExecResult.Completed) { "docker ps timed out" }
    check(listing.exitCode == 0) { "docker ps failed: ${listing.stderr}" }
    listing.stdout.lines().filter { it.isNotBlank() }.toSet()
}
