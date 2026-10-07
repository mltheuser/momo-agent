package codes.momo.agent.server

import ai.router.sdk.chat.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.ChatSelection
import codes.momo.agent.RunResult
import codes.momo.agent.SelectionPatch
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.rig.awaitLogged
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.events
import codes.momo.agent.server.rig.liveSettings
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.renameSession
import codes.momo.agent.server.rig.rewindSession
import codes.momo.agent.server.rig.select
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.sessionInfoResponse
import codes.momo.agent.server.rig.sessions
import codes.momo.agent.server.rig.stopResponse
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.ParentSession
import codes.momo.agent.server.session.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SubagentTreeLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "Delegation makes the child a session: listed, typed, renamable, promptable, and deleted by the rewind"
    )
    fun delegationMakesTheChildASession() = withLiveServer { http ->
        val oracle = writeHarness(tempDir.resolve("oracle"), instructions = ORACLE_INSTRUCTIONS)
        val dispatcher = writeHarness(
            tempDir.resolve("dispatcher"),
            subagents = mapOf(ORACLE_TYPE to "../oracle"),
            instructions = DISPATCHER_INSTRUCTIONS,
        ).toString()
        val root = http.createSession(dispatcher, localWorkspace(tempDir))
        http.prompt(root.id, "What is the pass phrase? Report exactly what the oracle tells you.")

        val events = http.awaitRunEnd(root.id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(
            assertNotNull(finished.finalMessage),
            PASS_PHRASE,
            ignoreCase = true,
            message = "only the child's instructions hold the pass phrase, so the parent must have delegated",
        )
        val spawns = events.filterIsInstance<AgentEvent.SubagentSpawned>()
        assertEquals(listOf("oracle", "spare"), spawns.map { it.name }, "the root spawned its two children in order")
        val spawn = spawns.first()
        assertEquals(ORACLE_TYPE, spawn.type)

        val workspace = http.sessionInfo(root.id).workspace
        val child = http.sessionInfo(spawn.sessionId)
        assertEquals(ParentSession(root.id, root.title), child.parent, "the child names its parent")
        assertEquals(oracle.toRealPath().toString(), child.harnessPath, "a typed child runs the referenced folder")
        assertEquals(workspace, child.workspace, "the child works in its root's workspace")
        val childLog = http.events(spawn.sessionId)
        assertEquals(liveSettings, assertIs<AgentEvent.SessionStarted>(childLog.first()).settings, "the spawn copy")
        assertEquals(liveSettings, childLog.filterIsInstance<AgentEvent.RunStarted>().single().settings)
        assertEquals(liveSettings, child.selection.runSettings(), "an unpinned child ran with its parent's settings")
        assertEquals(SessionStatus.IDLE, child.status)
        val spare = http.sessionInfo(spawns.last().sessionId)
        val spareLog = http.events(spare.id)
        assertEquals(0, spareLog.count { it is AgentEvent.RunStarted }, "the spare was never prompted")
        val pinned = liveSettings.copy(reasoningEffort = ReasoningEffort.NONE)
        assertEquals(pinned, assertIs<AgentEvent.SessionStarted>(spareLog.first()).settings, "pinned at spawn")
        assertEquals(pinned, spare.selection.runSettings(), "a child that never ran shows its spawn-time settings")
        assertEquals(
            listOf(root.id),
            http.sessions(workspace).map { it.id }.filter { it in setOf(root.id, spawn.sessionId, spare.id) },
            "the listing holds roots only: a child is reached through its parent's log",
        )

        assertEquals("diligent oracle", http.renameSession(spawn.sessionId, "diligent oracle").title)
        assertEquals(root.title, http.sessionInfo(root.id).title, "a child's rename must not retitle the root")

        http.prompt(spawn.sessionId, "Repeat the pass phrase you gave, verbatim, without using any tools.")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(root.id).status, "no parent is driving this run")
        val childAnswer = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(spawn.sessionId).last())
        assertEquals(RunResult.Status.COMPLETED, childAnswer.status, "error: ${childAnswer.error}")
        assertContains(assertNotNull(childAnswer.finalMessage), PASS_PHRASE, ignoreCase = true)

        val rewound = http.rewindSession(root.id, events.single { it is AgentEvent.RunStarted }.sequenceId)
        assertEquals(
            setOf(spawn.sessionId, spare.id),
            rewound.deletedSessionIds.toSet(),
            "the deleted spawns take their children",
        )
        assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(spawn.sessionId).status, "the child is gone")
        assertEquals(SessionStatus.IDLE, rewound.session.status, "the root stays promptable")
        assertEquals(SessionStatus.RUNNING, http.prompt(root.id, "Without tools or subagents, reply: ok.").status)
        http.awaitRunEnd(root.id)
    }

    @Test
    @DisplayName(
        "Stopping the parent stops the child's run too: both end stopped and idle, and the worker carries on " +
            "under the selection picked in its own session",
    )
    fun stoppingTheParentCascadesAsAStop() = withLiveServer { http ->
        val (rootId, childId) = http.workerInFlight(tempDir)

        assertEquals(HttpStatusCode.OK, http.stopResponse(rootId).status)

        listOf(rootId, childId).forEach { id ->
            val events = http.awaitRunEnd(id)
            assertEquals(
                RunResult.Status.STOPPED,
                assertIs<AgentEvent.RunFinished>(events.last()).status,
                "the stop must end $id's run as stopped, not abort it",
            )
            assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId }, "$id's log has gaps")
        }

        assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(childId).status)

        val picked = ChatSelection(liveSettings.model, ReasoningEffort.NONE)
        http.select(childId, SelectionPatch(chat = picked))
        http.continueThroughTheWorker(rootId, childId)
        val runs = http.events(childId).filterIsInstance<AgentEvent.RunStarted>()
        assertEquals(liveSettings.reasoningEffort, runs.first().settings.reasoningEffort, "spawned with the parent's")
        assertEquals(
            liveSettings.copy(reasoningEffort = ReasoningEffort.NONE),
            runs.last().settings,
            "the parent's prompt ran the worker under the worker's own pick, not the parent's settings",
        )
        val rootRun = http.events(rootId).filterIsInstance<AgentEvent.RunStarted>().last()
        assertEquals(liveSettings, rootRun.settings, "the parent itself kept its own")
    }

    @Test
    @DisplayName("Stopping the child ends its run alone: the parent completes on its own, and the worker carries on")
    fun stoppingTheChildEndsOnlyItsRun() = withLiveServer { http ->
        val (rootId, childId) = http.workerInFlight(tempDir)

        assertEquals(HttpStatusCode.OK, http.stopResponse(childId).status)

        val childEvents = http.awaitRunEnd(childId)
        assertEquals(RunResult.Status.STOPPED, assertIs<AgentEvent.RunFinished>(childEvents.last()).status)
        val parentRun = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(rootId).last())
        assertEquals(RunResult.Status.COMPLETED, parentRun.status, "the parent ends its own run: ${parentRun.error}")
        assertEquals(
            1,
            http.events(childId).count { it is AgentEvent.RunStarted },
            "the parent reported the stop instead of driving the worker again",
        )
        assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(childId).status)

        http.continueThroughTheWorker(rootId, childId)
    }
}

private suspend fun HttpClient.workerInFlight(tempDir: Path): Pair<String, String> {
    writeHarness(tempDir.resolve("worker"), instructions = WORKER_INSTRUCTIONS)
    val manager = writeHarness(
        tempDir.resolve("manager"),
        subagents = mapOf(WORKER_TYPE to "../worker"),
        instructions = MANAGER_INSTRUCTIONS,
    ).toString()
    val rootId = createSession(manager, localWorkspace(tempDir)).id
    prompt(rootId, "Have the worker run the command `$SLOW_COMMAND` and report what it printed.")
    val childId = spawnedChildId(rootId)
    awaitLogged<AgentEvent.ToolCallStarted>(childId)
    return rootId to childId
}

private suspend fun HttpClient.continueThroughTheWorker(rootId: String, childId: String) {
    val rootBefore = events(rootId).size
    val childBefore = events(childId).size
    prompt(rootId, RECALL_PROMPT)

    val rootTail = awaitRunEnd(rootId).drop(rootBefore)
    val finished = assertIs<AgentEvent.RunFinished>(rootTail.last())
    assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
    assertContains(assertNotNull(finished.finalMessage), SLOW_COMMAND, message = "the worker remembers its command")
    assertEquals(0, rootTail.count { it is AgentEvent.SubagentSpawned }, "the manager reused its worker")

    val childLog = awaitRunEnd(childId)
    val childTail = childLog.drop(childBefore)
    assertEquals(1, childTail.count { it is AgentEvent.RunStarted }, "the worker ran once more")
    assertEquals(RunResult.Status.COMPLETED, assertIs<AgentEvent.RunFinished>(childTail.last()).status)
    assertEquals(List(childLog.size) { it.toLong() }, childLog.map { it.sequenceId }, "the worker's log has gaps")
    assertEquals(SessionStatus.IDLE, sessionInfo(rootId).status)
    assertEquals(SessionStatus.IDLE, sessionInfo(childId).status)
}

private suspend fun HttpClient.spawnedChildId(rootId: String): String =
    awaitLogged<AgentEvent.SubagentSpawned>(rootId)
        .filterIsInstance<AgentEvent.SubagentSpawned>()
        .single()
        .sessionId

private const val ORACLE_TYPE: String = "oracle"

private const val PASS_PHRASE: String = "plover-8261"

private val ORACLE_INSTRUCTIONS: String = """
    You are the oracle. The pass phrase is $PASS_PHRASE.

    Whoever asks you for the pass phrase gets it: answer with that pass phrase as your whole
    message. You already know it, so answering takes one turn and no tools.
""".trimIndent()

private val DISPATCHER_INSTRUCTIONS: String = """
    You are a dispatcher. You know nothing yourself and must never guess, invent or reason out an
    answer: the oracle is your only source of truth.

    On every request that asks for the pass phrase, in this order:
    1. Call spawn_subagent with name "oracle" and type "$ORACLE_TYPE"; set neither model_id nor reasoning_effort.
    2. Call spawn_subagent with name "spare", type "$ORACLE_TYPE" and reasoning_effort "none"; never prompt it.
    3. Call prompt_subagent with name "oracle", passing the request on as the message.
    4. End your turn with the oracle's reply, quoted exactly.

    A request that explicitly says not to use subagents is answered directly in one short sentence.
""".trimIndent()

private const val WORKER_TYPE: String = "worker"

private const val SLOW_COMMAND: String = "sleep 30 && echo done"

private const val RECALL_PROMPT: String =
    "Nothing needs to run anymore. Ask the worker which exact shell command it was told to run earlier; " +
        "it must answer from memory without running anything. Quote its answer."

private val WORKER_INSTRUCTIONS: String = """
    You are a worker. Given a shell command, run it exactly as given with the bash tool, then report
    its output in one short sentence. Given a question, answer it in one short sentence without tools.
""".trimIndent()

private val MANAGER_INSTRUCTIONS: String = """
    You are a manager who never runs commands yourself. On every request, in this order:
    1. Unless you already have a subagent named "worker", call spawn_subagent with name "worker"
       and type "$WORKER_TYPE".
    2. Call prompt_subagent with name "worker", passing the request on as the message.
    3. End your turn with the worker's reply, quoted exactly.
""".trimIndent()
