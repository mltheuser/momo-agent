package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.session.ModelSelection
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

        val events = http.streamEvents(root.id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertContains(
            assertNotNull(finished.finalMessage),
            PASS_PHRASE,
            ignoreCase = true,
            message = "only the child's instructions hold the pass phrase, so the parent must have delegated",
        )
        val spawn = assertIs<AgentEvent.SubagentSpawned>(
            events.map { it.event }.single { it is AgentEvent.SubagentSpawned },
            "the root spawned exactly one child",
        )
        assertEquals(ORACLE_TYPE, spawn.type)
        http.awaitRunEnd(root.id)

        val child = http.sessionInfo(spawn.sessionId)
        assertEquals(root.id, child.parent, "the child names its parent")
        assertEquals(oracle.toRealPath().toString(), child.harnessPath, "a typed child runs the referenced folder")
        assertEquals(root.workspace, child.workspace, "the child works in its root's workspace")
        assertEquals(ModelSelection(liveChatModel), child.modelSelection, "the child ran the model its parent did")
        assertEquals(SessionStatus.IDLE, child.status)
        assertEquals(
            listOf(root.id),
            http.sessions(root.workspace).map { it.id }.filter { it == root.id || it == spawn.sessionId },
            "the listing holds roots only: a child is reached through its parent's log",
        )

        assertEquals("diligent oracle", http.renameSession(spawn.sessionId, "diligent oracle").title)
        assertEquals(root.title, http.sessionInfo(root.id).title, "a child's rename must not retitle the root")

        http.prompt(spawn.sessionId, "Repeat the pass phrase you gave, verbatim, without using any tools.")
        assertEquals(SessionStatus.IDLE, http.sessionInfo(root.id).status, "no parent is driving this run")
        val childAnswer = assertIs<AgentEvent.RunFinished>(http.streamEvents(spawn.sessionId).last().event)
        assertEquals(RunResult.Status.COMPLETED, childAnswer.status, "error: ${childAnswer.error}")
        assertContains(assertNotNull(childAnswer.finalMessage), PASS_PHRASE, ignoreCase = true)
        http.awaitRunEnd(spawn.sessionId)

        val rewound = http.rewindSession(root.id, spawn.sequenceId)
        assertEquals(listOf(spawn.sessionId), rewound.deletedSessionIds, "the deleted spawn takes its child")
        assertEquals(HttpStatusCode.NotFound, http.sessionInfoResponse(spawn.sessionId).status, "the child is gone")
        assertEquals(SessionStatus.IDLE, rewound.session.status, "the root stays attached and promptable")
        assertEquals(SessionStatus.RUNNING, http.prompt(root.id, "Without tools or subagents, reply: ok.").status)
        http.awaitRunEnd(root.id)
    }

    @Test
    @DisplayName("Stopping the parent stops the child's run too: both end stopped, idle and still there")
    fun stoppingTheParentCascadesAsAStop() = withLiveServer { http ->
        writeHarness(tempDir.resolve("worker"), instructions = WORKER_INSTRUCTIONS)
        val manager = writeHarness(
            tempDir.resolve("manager"),
            subagents = mapOf(WORKER_TYPE to "../worker"),
            instructions = MANAGER_INSTRUCTIONS,
        ).toString()
        val rootId = http.createSession(manager, localWorkspace(tempDir)).id
        http.prompt(rootId, "Have the worker run the command `sleep 30 && echo done` and report what it printed.")

        val childId = http.spawnedChildId(rootId)

        http.streamEvents(childId, until = { it is AgentEvent.ToolCallStarted })

        assertEquals(HttpStatusCode.OK, http.stopResponse(rootId).status)

        listOf(rootId, childId).forEach { id ->
            val events = http.streamEvents(id)
            assertEquals(
                RunResult.Status.STOPPED,
                assertIs<AgentEvent.RunFinished>(events.last().event).status,
                "the stop must end $id's run as stopped, not abort it",
            )
            assertEquals(List(events.size) { it.toLong() }, events.map { it.id }, "$id's log has gaps")
        }

        http.awaitRunEnd(rootId)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(rootId).status)
        assertEquals(SessionStatus.IDLE, http.sessionInfo(childId).status)
    }
}

private suspend fun HttpClient.spawnedChildId(rootId: String): String =
    assertIs<AgentEvent.SubagentSpawned>(
        streamEvents(rootId, until = { it is AgentEvent.SubagentSpawned }).last().event,
    ).sessionId

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
    1. Call spawn_subagent with name "oracle" and type "$ORACLE_TYPE".
    2. Call prompt_subagent with name "oracle", passing the request on as the message.
    3. End your turn with the oracle's reply, quoted exactly.

    A request that explicitly says not to use subagents is answered directly in one short sentence.
""".trimIndent()

private const val WORKER_TYPE: String = "worker"

private val WORKER_INSTRUCTIONS: String = """
    You are a worker. Run exactly the shell command you are given with the bash tool, verbatim,
    then report its output in one short sentence.
""".trimIndent()

private val MANAGER_INSTRUCTIONS: String = """
    You are a manager who never runs commands yourself. On every request, in this order:
    1. Call spawn_subagent with name "worker" and type "$WORKER_TYPE".
    2. Call prompt_subagent with name "worker", passing the request on as the message.
    3. End your turn with the worker's reply, quoted exactly.
""".trimIndent()
