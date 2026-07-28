package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlm
import codes.momo.agent.FakeLlmRule
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.unusedAiRouterClient
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.ktor.server.cio.CIO as ServerCIO

/** Runs [block] against a full server over a registry on [tempDir]'s data dir, with no LLM reachable. */
internal fun withSessionServer(tempDir: Path, wait: Duration = RUN_WAIT, block: suspend (HttpClient) -> Unit) {
    unusedAiRouterClient().use { client -> withSessionServer(tempDir, client, wait, block) }
}

/** Runs [block] against a full server over a registry on [tempDir]'s data dir, backed by [client]'s LLM. */
internal fun withSessionServer(
    tempDir: Path,
    client: AiRouterClient,
    wait: Duration = RUN_WAIT,
    block: suspend (HttpClient) -> Unit,
) {
    SessionRegistry(tempDir.resolve("data"), client).use { registry ->
        withServer(registry, client, wait, block)
    }
}

/** Runs [block] against a full server over [tempDir] whose LLM is a [FakeLlm] over [rules]. */
internal fun withFakeSessionServer(
    tempDir: Path,
    vararg rules: FakeLlmRule,
    block: suspend (HttpClient) -> Unit,
) {
    withFakeSessionServer(tempDir, FakeLlm(*rules), block = block)
}

/**
 * Runs [block] against a full server over [tempDir] whose LLM is [fake].
 *
 * A server-started run is nobody's return value, so a request the script
 * cannot answer reaches [block] only as that run's `error` outcome. Whatever
 * then fails is given the fake's own diagnostic, which is otherwise the one
 * thing the failure would be missing — and a starved fake fails a case in
 * more ways than an assertion: a request bound blown, a frame that never
 * arrived, an outcome nothing decoded.
 *
 * A case can also *pass* over such a run, asserting only the metadata and
 * statuses an error outcome leaves standing, so an unanswered request fails
 * the case on the way out too — unless outrunning the script is what the case
 * is about ([outrunsItsScript]).
 */
internal fun withFakeSessionServer(
    tempDir: Path,
    fake: FakeLlm,
    outrunsItsScript: Boolean = false,
    block: suspend (HttpClient) -> Unit,
) {
    withFakeLlm(fake) { client -> withSessionServer(tempDir, client, block = block) }
    val unanswered = fake.unansweredRequests
    if (!outrunsItsScript && unanswered.isNotEmpty()) {
        fail(
            "the case passed, but a run in it reached a request the script could not answer — so what it " +
                "asserted held over a run that failed:\n\n${unanswered.joinToString("\n\n")}",
        )
    }
}

/**
 * Runs [block] over [fake]'s SDK client and gives whatever fails the fake's
 * own diagnostic — the repair [withFakeSessionServer] makes, for a case
 * driving a registry of its own rather than a server.
 */
internal fun <T> withFakeLlm(fake: FakeLlm, block: (AiRouterClient) -> T): T =
    runCatching { fake.client().use(block) }
        .getOrElse { failure -> throw failure.explainedBy(fake) }

/**
 * [this] with what the fake could not answer appended — unless it is a
 * cancellation, which is load-bearing, or a skipped case, which is not a
 * failure at all.
 */
private fun Throwable.explainedBy(fake: FakeLlm): Throwable {
    val unanswered = fake.unansweredRequests
    return when {
        this is CancellationException || this is TestAbortedException || unanswered.isEmpty() -> this
        else -> AssertionError("$message\n\n${unanswered.joinToString("\n\n")}", this)
    }
}

/**
 * Runs [block] against the server's real routing and serialization over a
 * loopback socket — the same engine `Main.kt` runs — with [registry] behind
 * it and [client] backing `/v1/models`.
 */
private fun withServer(
    registry: SessionRegistry,
    client: AiRouterClient,
    wait: Duration,
    block: suspend (HttpClient) -> Unit,
) {
    val server = embeddedServer(ServerCIO, port = 0, host = LOOPBACK) { agentServer(registry, client) }
    try {
        server.start(wait = false)
        runBlocking {
            val port = server.engine.resolvedConnectors().first().port
            serverHttpClient("http://$LOOPBACK:$port", wait).use { http -> block(http) }
        }
    } finally {
        server.stop(gracePeriodMillis = 0, timeoutMillis = SHUTDOWN_TIMEOUT.inWholeMilliseconds)
    }
}

/** Prompts run the model the mocked tier's responses report, so only a case about the model names one. */
internal suspend fun HttpClient.prompt(sessionId: String, prompt: String): SessionInfo =
    prompt(sessionId, prompt, TEST_RUN_SETTINGS.model)

internal suspend fun HttpClient.promptResponse(sessionId: String, prompt: String): HttpResponse =
    promptResponse(sessionId, prompt, TEST_RUN_SETTINGS.model)

/**
 * Streams [sessionId]'s events to its run's terminal one, asserting that the
 * run ended as [expected] inside [FAIL_FAST_BOUND] and that the session reads
 * idle once it did — the mechanics every case about a run ending by itself
 * shares, whatever ends it.
 */
internal suspend fun HttpClient.assertRunEndsAtOnce(sessionId: String, expected: RunResult.Status) {
    val started = TimeSource.Monotonic.markNow()
    val events = streamEvents(sessionId)
    val elapsed = started.elapsedNow()

    assertEquals(expected, assertIs<AgentEvent.RunFinished>(events.last().event).status)
    assertTrue(elapsed < FAIL_FAST_BOUND, "expected the run to end inside $FAIL_FAST_BOUND, took $elapsed")
    // Awaited, and only once the bound above is measured, since the server's
    // claim on the run it just ended can outlive the terminal frame the stream
    // returned on.
    awaitRunEnd(sessionId)
    assertEquals(SessionStatus.IDLE, sessionInfo(sessionId).status)
}

// ─── Waits below the HTTP surface ─────────────────────────────────────

/** Waits until [id]'s active run ends, however it ends. */
internal suspend fun SessionRegistry.awaitRunEnd(id: String) {
    val ended = withTimeoutOrNull(RUN_WAIT) {
        while (info(id).status == SessionStatus.RUNNING) {
            delay(POLL_INTERVAL)
        }
        true
    }
    if (ended == null) {
        fail("the run on $id never ended within $RUN_WAIT. Its status: ${info(id).status}.")
    }
}

/** Waits until [id]'s stored log holds an event matching [until]; the log is flushed per event. */
internal suspend fun SessionStore.awaitLoggedEvent(id: String, until: (AgentEvent) -> Boolean) {
    val arrived = withTimeoutOrNull(RUN_WAIT) {
        while (readEvents(id).none(until)) {
            delay(POLL_INTERVAL)
        }
        true
    }
    if (arrived == null) {
        fail("no awaited event reached $id's stored log within $RUN_WAIT. Its log: ${readEvents(id).map { it::class }}")
    }
}

/**
 * Ceiling on the waits below, and the one a served client carries unless its
 * suite asks for another. The LLM answers below the network and the only real
 * work is the odd bash command, so this is a backstop against a wedge, not a
 * budget — a suite whose requests do work of their own passes a ceiling
 * proportionate to that work instead.
 */
private val RUN_WAIT: Duration = 30.seconds

/**
 * How long a run that ends by itself may take to reach its subscriber. The
 * timed window is a loopback SSE handshake and a decode — plus, for the case
 * whose run gets as far as a tool, one bash process — measured at ~20 ms, so
 * two orders of magnitude of slack leave only an actual wait able to breach it,
 * and the only wait on offer is [RUN_WAIT], what a run without a terminal event
 * costs.
 */
private val FAIL_FAST_BOUND: Duration = 2.seconds

private val SHUTDOWN_TIMEOUT: Duration = 5.seconds

private const val LOOPBACK: String = "127.0.0.1"
