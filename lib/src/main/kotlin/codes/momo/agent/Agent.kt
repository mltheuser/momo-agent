package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ReasoningEffort
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolDefinition
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.SUBAGENT_TOOL_NAMES
import codes.momo.agent.internal.AgentEventEmitter
import codes.momo.agent.internal.RETRY_BACKOFFS
import codes.momo.agent.internal.RunBudgets
import codes.momo.agent.internal.SessionState
import codes.momo.agent.internal.ZERO_USAGE
import codes.momo.agent.internal.awaitsModel
import codes.momo.agent.internal.coreToolRegistry
import codes.momo.agent.internal.plus
import codes.momo.agent.internal.resolvePromptAttachments
import codes.momo.agent.internal.restoredSession
import codes.momo.agent.internal.retryTransientFailures
import codes.momo.agent.internal.systemMessage
import codes.momo.agent.internal.toolCallRepairs
import codes.momo.agent.internal.toolResultMessage
import codes.momo.agent.internal.userMessage
import codes.momo.agent.subagent.Subagents
import codes.momo.agent.tool.TOOL_TIMEOUT
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

@Suppress("TooManyFunctions")
public class Agent internal constructor(
    private val harness: Harness,
    private val client: AiRouterClient,
    private val environment: ExecutionEnvironment,
    private val eventListener: AgentEventListener,
    session: SessionState,
) {

    public constructor(
        harness: Harness,
        client: AiRouterClient,
        environment: ExecutionEnvironment,
        title: String,
        eventListener: AgentEventListener = NoOpAgentEventListener,
    ) : this(harness, client, environment, eventListener, SessionState.Fresh(title))

    internal val subagents: Subagents =
        Subagents(this, harness.subagents.keys, client, session.spawned)

    private val depth: Int = session.depth

    private val registry: ToolRegistry

    private val toolDefinitions: List<ToolDefinition>

    init {
        val coreRegistry =
            coreToolRegistry(environment.workspacePath, environment.privilege, subagents, harness.subagents)
        harness.requireToolsKnown(coreRegistry.names)

        val offered = if (harness.subagents.isNotEmpty() && depth < RunBudgets.MAX_SUBAGENT_DEPTH) {
            harness.tools + SUBAGENT_TOOL_NAMES
        } else {
            harness.tools
        }
        registry = coreRegistry.restrictedTo(offered)
        toolDefinitions = registry.definitions(offered)
    }

    private val running = AtomicBoolean(false)

    public val isRunning: Boolean
        get() = running.get()

    private val emitter = AgentEventEmitter(eventListener, session.nextSequenceId)

    public val sessionId: String = session.id

    public var title: String = session.title
        set(value) {
            field = value
            emitter.emit { id, at -> AgentEvent.SessionRenamed(id, at, value) }
        }

    public fun recordModelSelection(model: String, reasoningEffort: ReasoningEffort? = null) {
        emitter.emit { id, at -> AgentEvent.ModelSelected(id, at, model, reasoningEffort) }
    }

    private val history: MutableList<ChatMessage> = mutableListOf<ChatMessage>().apply {
        add(systemMessage(harness.instructions))
        addAll(session.conversation)
    }

    @Volatile // stop() reads it from the caller's thread; the run writes it from its own.
    private var currentRun: RunState? = null

    init {
        if (session is SessionState.Fresh) {
            emitter.emit { id, at ->
                AgentEvent.SessionStarted(
                    sequenceId = id,
                    timestampMillis = at,
                    sessionId = sessionId,
                    title = title,
                    harnessPath = harness.folder?.toString(),
                    workspace = environment.workspacePath,
                    parent = session.parent,
                    depth = depth,
                )
            }
        }
    }

    public suspend fun send(text: String, settings: RunSettings): RunResult = guardedRun(text, settings)

    public suspend fun retry(settings: RunSettings): RunResult {
        require(history.last().awaitsModel) {
            "Nothing to retry: the conversation is not waiting on the model."
        }
        return guardedRun(text = null, settings)
    }

    private suspend fun guardedRun(text: String?, settings: RunSettings): RunResult {
        check(running.compareAndSet(false, true)) {
            "A run is already running on this agent — await the active one before starting another."
        }

        val run = RunState(settings, loop = Job(coroutineContext[Job]))
        try {
            return executeRun(text, run)
        } finally {
            run.loop.complete()
            running.set(false)

            run.ended.complete()
        }
    }

    private fun emitRunOpening(
        text: String?,
        settings: RunSettings,
        attachments: List<AgentEvent.RunStarted.Attachment>,
    ) {
        emitter.emit { id, at ->
            when (text) {
                null -> AgentEvent.RunResumed(id, at, settings.model, settings.reasoningEffort)
                else -> AgentEvent.RunStarted(id, at, text, settings.model, settings.reasoningEffort, attachments)
            }
        }
    }

    public suspend fun stop() {
        val run = currentRun ?: return
        // The cause is what makes this a stop, not an abort: every run cancelled by it, child runs the loop is
        // awaiting included, records STOPPED and stays promptable. A cancel without it records nothing.
        run.loop.cancel(RunStoppedException())
        run.ended.join()
    }

    private suspend fun executeRun(text: String?, run: RunState): RunResult {
        currentRun = run
        val attachments = if (text == null) emptyList() else resolvePromptAttachments(text, environment)
        if (text != null) {
            history += userMessage(text, attachments)
        }

        var status: RunResult.Status? = null
        try {
            emitRunOpening(text, run.settings, attachments)
            status = withContext(run.loop) { runLoop(run) }
        } catch (cancellation: CancellationException) {
            if (!cancellation.isRunStopped()) {
                throw cancellation
            }
            status = run.decided ?: RunResult.Status.STOPPED
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Exception, never Throwable: a JVM Error propagates and leaves the run without a run_finished.
            run.failure = failure
            status = RunResult.Status.ERROR
        } finally {
            currentRun = null

            history += toolCallRepairs(history, run.startedCallIds, status)
        }
        val result = RunResult(
            status = checkNotNull(status),
            finalMessage = run.finalMessage,
            usage = run.usage,
            turnsUsed = run.turnsUsed,
            elapsed = run.elapsed,
            error = run.failure,
        )
        if (result.status == RunResult.Status.ERROR) {
            try {
                logger.error("A run on session $sessionId ended in error.", result.error)
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            }
        }
        emitter.emitRunFinished(result)
        return result
    }

    private suspend fun runLoop(run: RunState): RunResult.Status {
        while (true) {
            coroutineContext.ensureActive()
            if (run.remaining <= Duration.ZERO) {
                return run.decide(RunResult.Status.TIMEOUT)
            }
            val response = takeTurn(run)
            val outcome = turnOutcome(run, response)
            if (outcome != null) {
                return run.decide(outcome)
            }
            for (call in response.message.toolCalls.orEmpty()) {
                coroutineContext.ensureActive()
                executeCall(run, call)
            }
        }
    }

    private fun turnOutcome(run: RunState, response: ChatResponse): RunResult.Status? {
        val toolCalls = response.message.toolCalls.orEmpty()
        return when {
            response.finishReason == FINISH_REASON_ERROR -> {
                run.failure = IllegalStateException("the LLM reported a failed response (finish_reason 'error').")
                RunResult.Status.ERROR
            }

            toolCalls.isEmpty() -> {
                run.finalMessage = response.textContent
                RunResult.Status.COMPLETED
            }

            run.turnsUsed >= RunBudgets.MAX_TURNS -> RunResult.Status.TURNS_EXHAUSTED

            else -> null
        }
    }

    private suspend fun takeTurn(run: RunState): ChatResponse {
        val request = ChatRequest(
            model = run.settings.model,
            messages = history.toList(),
            reasoningEffort = run.settings.reasoningEffort,
            tools = toolDefinitions,
        )
        emitter.emit { id, at -> AgentEvent.LlmCallStarted(id, at, turn = run.turnsUsed + 1) }
        val response = retryTransientFailures(
            backoffs = RETRY_BACKOFFS,
            onRetry = { cause, attempt, backoff ->
                emitter.emit { id, at ->
                    AgentEvent.LlmCallRetried(id, at, cause.message ?: cause.toString(), attempt, backoff)
                }
            },
        ) { client.chat(request) }
        run.turnsUsed++
        run.usage += response.usage
        history += response.message
        emitter.emit { id, at ->
            AgentEvent.LlmCallFinished(id, at, response.message, response.usage, response.finishReason)
        }
        emitter.emit { id, at ->
            AgentEvent.BudgetUpdated(
                sequenceId = id,
                timestampMillis = at,
                turnsUsed = run.turnsUsed,
                turnsRemaining = RunBudgets.MAX_TURNS - run.turnsUsed,
                elapsed = run.elapsed,
            )
        }
        return response
    }

    private suspend fun executeCall(run: RunState, call: ToolCall) {
        run.startedCallIds += call.id
        emitter.emit { id, at ->
            AgentEvent.ToolCallStarted(id, at, call.id, call.function.name, call.function.arguments)
        }
        val timeout = minOf(TOOL_TIMEOUT, run.remaining.coerceAtLeast(Duration.ZERO))
        val execution = registry.execute(call.function.name, call.function.arguments, environment, timeout)
        val media = execution.result.media
        history += toolResultMessage(call.id, execution.result.text, media)
        emitter.emit { id, at ->
            AgentEvent.ToolCallFinished(
                sequenceId = id,
                timestampMillis = at,
                callId = call.id,
                resultText = execution.result.text,
                outcome = execution.result.outcome,
                duration = execution.duration,
                truncated = execution.truncated,
                media = media,
            )
        }
    }

    internal fun spawnChild(
        name: String,
        type: String,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
    ): Agent {
        val childHarness = harness.subagents.getValue(type).harness
        val session = SessionState.Fresh(title = name, parent = sessionId, depth = depth + 1)

        val listener = eventListener.subagentListener(name, session.id)
        emitter.emit { id, at ->
            AgentEvent.SubagentSpawned(id, at, name, session.id, type, modelId, reasoningEffort)
        }
        return Agent(
            harness = childHarness,
            client = client,
            environment = environment,
            eventListener = listener,
            session = session,
        )
    }

    internal suspend fun reviveChild(name: String, sessionId: String, type: String?): Agent? {
        val events = eventListener.storedEventsFor(sessionId) ?: return null
        if (type == null) {
            throw SubagentRevivalException(
                "subagent '$name' was spawned without a type " +
                    "and cannot be revived — spawn a fresh subagent instead.",
            )
        }
        val childHarness = harness.subagents[type]?.harness ?: throw SubagentRevivalException(
            "subagent '$name' was spawned as type '$type', which the harness does not declare. " +
                "Declared types: ${harness.subagents.keys.ifEmpty { setOf("(none)") }.joinToString(", ")}.",
        )
        val session = restoredSession(events, childHarness)
        check(session.depth == depth + 1) {
            "the stored log for subagent '$name' records depth ${session.depth}, expected ${depth + 1}."
        }
        return Agent(
            harness = childHarness,
            client = client,
            environment = environment,
            eventListener = eventListener.subagentListener(name, sessionId),
            session = session,
        )
    }

    internal suspend fun <T> awaitingChildRun(block: suspend (RunSettings) -> T): T {
        val active = checkNotNull(currentRun) { "a child can only be awaited from within a run." }
        val blockedSince = TimeSource.Monotonic.markNow()
        try {
            return block(active.settings)
        } finally {
            active.blocked += blockedSince.elapsedNow()
        }
    }

    private inner class RunState(val settings: RunSettings, val loop: CompletableJob) {

        val start = TimeSource.Monotonic.markNow()

        val ended: CompletableJob = Job()

        var decided: RunResult.Status? = null

        var turnsUsed: Int = 0
        var usage: ChatUsage = ZERO_USAGE
        var finalMessage: String? = null
        var failure: Exception? = null

        val startedCallIds: MutableSet<String> = mutableSetOf()

        var blocked: Duration = Duration.ZERO

        val elapsed: Duration
            get() = start.elapsedNow() - blocked

        val remaining: Duration
            get() = RunBudgets.MAX_WALL_CLOCK - elapsed

        fun decide(status: RunResult.Status): RunResult.Status = status.also { decided = it }
    }

    public companion object {

        public fun load(
            events: List<AgentEvent>,
            harness: Harness,
            client: AiRouterClient,
            environment: ExecutionEnvironment,
            eventListener: AgentEventListener = NoOpAgentEventListener,
        ): Agent = Agent(harness, client, environment, eventListener, restoredSession(events, harness))
    }
}

private val logger: Logger = LoggerFactory.getLogger(Agent::class.java)

private class RunStoppedException : CancellationException("The run was stopped.")

private fun CancellationException.isRunStopped(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it is RunStoppedException }

public suspend fun Agent.subagentBySessionId(sessionId: String): Agent? = subagents.childBySessionId(sessionId)

public suspend fun Agent.liveSubagentBySessionId(sessionId: String): Agent? =
    subagents.liveChildBySessionId(sessionId)

private fun AgentEventListener.subagentListener(name: String, sessionId: String): AgentEventListener = try {
    listenerForSubagent(name, sessionId)
} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    NoOpAgentEventListener
}

private fun AgentEventEmitter.emitRunFinished(result: RunResult) {
    emit { id, at ->
        AgentEvent.RunFinished(
            sequenceId = id,
            timestampMillis = at,
            status = result.status,
            finalMessage = result.finalMessage,
            usage = result.usage,
            turnsUsed = result.turnsUsed,
            elapsed = result.elapsed,
            error = result.error?.let(AgentEvent.RunFinished.Error::from),
        )
    }
}

private val ToolResult.media: AgentEvent.ToolCallFinished.Media?
    get() = (this as? ToolResult.Image)?.let { AgentEvent.ToolCallFinished.Media(it.mimeType, it.base64Data) }

private val ToolResult.outcome: AgentEvent.ToolCallFinished.Outcome
    get() = when (this) {
        is ToolResult.Success, is ToolResult.Image -> AgentEvent.ToolCallFinished.Outcome.SUCCESS
        is ToolResult.Error -> AgentEvent.ToolCallFinished.Outcome.ERROR
        is ToolResult.TimedOut -> AgentEvent.ToolCallFinished.Outcome.TIMED_OUT
    }

private const val FINISH_REASON_ERROR: String = "error"
