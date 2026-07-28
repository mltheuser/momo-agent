package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlmRule
import codes.momo.agent.assistantResponse
import codes.momo.agent.fromRootAgent
import codes.momo.agent.fromSubagent
import codes.momo.agent.harness.writeHarness
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.promptSubagentCall
import codes.momo.agent.spawnSubagentCall
import codes.momo.agent.toolCallResponse
import io.ktor.client.HttpClient
import java.nio.file.Path
import kotlin.test.assertIs

/*
 * What the server's subagent cases share: a harness whose agent may spawn,
 * the one spawning run they open with, and how a case gets hold of the child
 * that run produced.
 */

/** A harness folder under [tempDir] whose agent may spawn children running that same folder. */
internal fun subagentHarness(tempDir: Path): String =
    writeHarness(tempDir.resolve("harness"), tools = listOf("bash"), subagents = mapOf("self" to "."))
        .toString()

/** The turns of a run in which the root spawns and primes the child "helper". */
internal fun spawnHelperRules(): Array<FakeLlmRule> = arrayOf(
    onOpeningTurn(
        toolCallResponse(
            spawnSubagentCall(id = "call-1", name = "helper"),
            promptSubagentCall(id = "call-2", name = "helper", message = PRIME_MESSAGE),
        ),
        saying = SPAWN_PROMPT,
    ).fromRootAgent(),
    onOpeningTurn(assistantResponse(finishReason = "stop", text = CHILD_ANSWER), saying = PRIME_MESSAGE)
        .fromSubagent(),
    onToolResults(
        assistantResponse(finishReason = "stop", text = "spawned"),
        saying = CHILD_ANSWER,
    ).fromRootAgent(),
)

/** Waits for the spawn to appear on the parent's stream and returns the child's session ID. */
internal suspend fun spawnedChildId(http: HttpClient, rootId: String): String =
    assertIs<AgentEvent.SubagentSpawned>(
        http.streamEvents(rootId, until = { it is AgentEvent.SubagentSpawned }).last().event,
    ).sessionId

/** Runs [block] after a run in which the root spawned the child "helper" and went idle. */
internal fun withSpawnedChild(
    tempDir: Path,
    block: suspend (http: HttpClient, rootId: String, childId: String) -> Unit,
) {
    withFakeSessionServer(tempDir, *spawnHelperRules()) { http ->
        val rootId = http.createSession(subagentHarness(tempDir), localWorkspace(tempDir)).id
        http.prompt(rootId, SPAWN_PROMPT)
        val childId = spawnedChildId(http, rootId)
        http.awaitRunEnd(rootId)
        block(http, rootId, childId)
    }
}

/**
 * Waits until [sessionId]'s [run]th run is inside a tool call, so a command
 * issued next cannot land before the run it races has begun. The stream
 * replays the session from its start, which is why the runs are counted: a
 * tool call an earlier run made must not end this wait. What holds a run there
 * is real time in a tool — the fake withholds no reply, and so has none to
 * release — but the wait is what makes the race deterministic: the sleep only
 * has to outlast one command's flight over loopback.
 */
internal suspend fun awaitBlockedInATool(http: HttpClient, sessionId: String, run: Int = 1) {
    var started = 0
    http.streamEvents(sessionId) { event ->
        if (event is AgentEvent.RunStarted) started++
        event is AgentEvent.ToolCallStarted && started >= run
    }
}

/** The prompt every spawning run here opens with; the rules key their opening turn on it. */
internal const val SPAWN_PROMPT: String = "spawn and prime the helper"

/** What the root prompts the freshly spawned child with — the child's first run. */
internal const val PRIME_MESSAGE: String = "get ready"

/** The child's final message, and so the newest tool result of the turn that follows the spawn batch. */
internal const val CHILD_ANSWER: String = "the helper is ready"
