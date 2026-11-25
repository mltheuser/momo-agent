package codes.momo.agent.server.storage

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal val SESSION_RETENTION: Duration = 90.days

internal fun SessionStore.deleteExpiredTrees() {
    val expiredBefore = System.currentTimeMillis() - SESSION_RETENTION.inWholeMilliseconds
    val forest = forest()
    val deleted = forest.roots.map(forest::subtreeOf).count { tree -> deleteIfExpired(tree, expiredBefore) }
    if (deleted > 0) {
        logger.info("Session trees expired: $deleted.")
    }
}

private fun SessionStore.deleteIfExpired(tree: List<String>, expiredBefore: Long): Boolean = runCatching {
    val expired = tree.all { lastWrittenMillis(it) < expiredBefore }
    if (expired) {
        tree.asReversed().forEach(::delete)
    }
    expired
}.onFailure { logger.warn("Session tree ${tree.first()} could not be expired.", it) }.getOrDefault(false)

private val logger: Logger = LoggerFactory.getLogger(SessionStore::class.java)
