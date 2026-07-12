package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolDefinition
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import codes.momo.agent.tool.coreToolRegistry
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One agent session over a loaded [Harness]: [send] advances the LLM/tool
 * loop against the session's accumulated conversation, under [RunBudgets].
 * Everything the session does is reported to its [AgentEventListener] as
 * the session's [AgentEvent] log.
 *
 * Collaborator lifecycles stay with the embedder: the agent never closes
 * [client] or [environment].
 *
 * @throws HarnessValidationException when the harness names a tool the
 *   library does not provide.
 */
public class Agent internal constructor(
    private val harness: Harness,
    private val client: AiRouterClient,
    private val environment: ExecutionEnvironment,
    eventListener: AgentEventListener,
    private val budgets: RunBudgets,
    session: SessionState,
) {

    /** Creates a fresh session titled [title], with a generated [sessionId]. */
    public constructor(
        harness: Harness,
        client: AiRouterClient,
        environment: ExecutionEnvironment,
        title: String,
        eventListener: AgentEventListener = NoOpAgentEventListener,
    ) : this(harness, client, environment, eventListener, RunBudgets(), SessionState.Fresh(title))

    private val registry: ToolRegistry

    private val toolDefinitions: List<ToolDefinition>

    init {
        val coreRegistry = coreToolRegistry()
        harness.requireToolsKnown(coreRegistry.names)
        registry = coreRegistry.restrictedTo(harness.tools)
        toolDefinitions = registry.definitions(harness.tools)
    }

    private val running = AtomicBoolean(false)

    private val emitter = AgentEventEmitter(eventListener, session.nextSequenceId)

    /** Stable identity of this session, recoverable from its event log. */
    public val sessionId: String = when (session) {
        is SessionState.Fresh -> UUID.randomUUID().toString()
        is SessionState.Restored -> session.id
    }

    /** User-facing session title; every assignment emits [AgentEvent.SessionRenamed]. */
    public var title: String = session.title
        set(value) {
            field = value
            emitter.emit { id, at -> AgentEvent.SessionRenamed(id, at, value) }
        }

    private val history: MutableList<ChatMessage> = mutableListOf<ChatMessage>().apply {
        add(textMessage(ROLE_SYSTEM, systemPromptFor(harness, environment.workspacePath)))
        addAll(session.conversation)
    }

    init {
        if (session is SessionState.Fresh) {
            emitter.emit { id, at -> AgentEvent.SessionStarted(id, at, sessionId, title) }
        }
    }

    /**
     * Sends [text] as the next user message and runs the loop to a terminal
     * outcome: each turn is one LLM call, followed by executing every
     * requested tool call sequentially, in order, until the model answers
     * without tool calls or a budget ends the run. Budget breaches and
     * terminal LLM failures are reported through the returned
     * [RunResult], never thrown.
     *
     * Runs accumulate: each continues the previous conversation with
     * fresh budget counters. After every outcome — cancellation included —
     * the stored conversation stays well-formed for the next call: tool
     * calls the run never finished are answered with synthesized aborted
     * results.
     *
     * @throws IllegalArgumentException when [text] is blank.
     * @throws IllegalStateException when a send is already running.
     */
    public suspend fun send(text: String): RunResult {
        require(text.isNotBlank()) { "A user message must not be blank." }
        check(running.compareAndSet(false, true)) {
            "send() is already running on this agent — await the active call before sending another."
        }
        try {
            return executeRun(text)
        } finally {
            running.set(false)
        }
    }

    private suspend fun executeRun(text: String): RunResult {
        val run = RunState()
        history += userMessage(text)
        emitter.emit { id, at -> AgentEvent.RunStarted(id, at, text) }
        val status = try {
            runLoop(run)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            run.failure = failure
            RunResult.Status.ERROR
        } finally {
            // A finally so the repair also runs on external cancellation.
            history += abortedToolResults(history)
        }
        val result = RunResult(
            status = status,
            finalMessage = run.finalMessage,
            transcript = history.toList(),
            usage = run.usage,
            turnsUsed = run.turnsUsed,
            elapsed = run.elapsed,
            error = run.failure,
        )
        emitter.emit { id, at ->
            AgentEvent.RunFinished(
                sequenceId = id,
                timestampMillis = at,
                status = result.status,
                finalMessage = result.finalMessage,
                usage = result.usage,
                turnsUsed = result.turnsUsed,
                elapsed = result.elapsed,
            )
        }
        return result
    }

    /**
     * Advances the run until an outcome: while wall clock remains, each
     * pass takes one LLM turn, then executes its tool calls.
     */
    private suspend fun runLoop(run: RunState): RunResult.Status {
        while (true) {
            if (run.remaining <= Duration.ZERO) {
                return RunResult.Status.TIMEOUT
            }
            val response = takeTurn(run)
            val outcome = turnOutcome(run, response)
            if (outcome != null) {
                return outcome
            }
            for (call in response.message.toolCalls.orEmpty()) {
                executeCall(run, call)
            }
        }
    }

    /** The run-ending status this turn produced, or null when the loop continues with its tool calls. */
    private fun turnOutcome(run: RunState, response: ChatResponse): RunResult.Status? {
        val toolCalls = response.message.toolCalls.orEmpty()
        return when {
            response.finishReason == FINISH_REASON_ERROR -> {
                // A provider-side failure can arrive as a successful HTTP
                // response; its text must not read as the model's answer.
                run.failure = IllegalStateException("the LLM reported a failed response (finish_reason 'error').")
                RunResult.Status.ERROR
            }

            toolCalls.isEmpty() -> {
                run.finalMessage = response.textContent
                RunResult.Status.COMPLETED
            }

            // The pending calls stay unexecuted: no LLM call is left to
            // ever see their results.
            run.turnsUsed >= budgets.maxTurns -> RunResult.Status.TURNS_EXHAUSTED

            else -> null
        }
    }

    /** One turn: one successful LLM call — retries cost wall-clock, not turns. */
    private suspend fun takeTurn(run: RunState): ChatResponse {
        val request = ChatRequest(model = harness.model, messages = history.toList(), tools = toolDefinitions)
        emitter.emit { id, at -> AgentEvent.LlmCallStarted(id, at, turn = run.turnsUsed + 1) }
        val response = retryTransientFailures(
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
                turnsRemaining = budgets.maxTurns - run.turnsUsed,
                elapsed = run.elapsed,
            )
        }
        return response
    }

    /** One dispatched (or registry-rejected) call. */
    private suspend fun executeCall(run: RunState, call: ToolCall) {
        emitter.emit { id, at ->
            AgentEvent.ToolCallStarted(id, at, call.id, call.function.name, call.function.arguments)
        }
        val timeout = minOf(Budgets.TOOL_TIMEOUT, run.remaining.coerceAtLeast(Duration.ZERO))
        val execution = registry.execute(call.function.name, call.function.arguments, environment, timeout)
        history += toolResultMessage(call.id, execution.result.text)
        emitter.emit { id, at ->
            AgentEvent.ToolCallFinished(
                sequenceId = id,
                timestampMillis = at,
                callId = call.id,
                resultText = execution.result.text,
                outcome = execution.result.outcome,
                duration = execution.duration,
                truncated = execution.truncated,
            )
        }
    }

    /** Mutable accounting for one [send] run. */
    private inner class RunState {

        val start = TimeSource.Monotonic.markNow()

        var turnsUsed: Int = 0
        var usage: ChatUsage = ZERO_USAGE
        var finalMessage: String? = null
        var failure: Exception? = null

        val elapsed: Duration
            get() = start.elapsedNow()

        val remaining: Duration
            get() = budgets.maxWallClock - elapsed
    }

    public companion object {

        /**
         * Reconstructs a session from its stored event log: the transcript
         * is derived from the log's verbatim payloads under the system
         * prompt [harness] produces now, tool calls the log left unanswered
         * are answered with synthesized aborted results, and new events
         * continue the log's sequence IDs. Loading emits nothing.
         *
         * @throws IllegalArgumentException when [events] is not a stored
         *   session log (its first event must be
         *   [AgentEvent.SessionStarted]).
         * @throws HarnessValidationException when the log calls tools
         *   [harness] does not include.
         */
        public fun load(
            events: List<AgentEvent>,
            harness: Harness,
            client: AiRouterClient,
            environment: ExecutionEnvironment,
            eventListener: AgentEventListener = NoOpAgentEventListener,
        ): Agent = Agent(harness, client, environment, eventListener, RunBudgets(), restoredSession(events, harness))
    }
}

/**
 * The system prompt: the harness instructions plus the library-owned facts
 * about the workspace and how to reach the user.
 */
internal fun systemPromptFor(harness: Harness, workspacePath: String): String =
    harness.instructions.trimEnd() +
        "\n\nThe workspace root is $workspacePath. File paths passed to tools must be absolute." +
        "\n\nThe user is not watching you work and sees only your final message; their next message " +
        "may take hours or days to arrive. Work autonomously and end your turn only when you are " +
        "done or genuinely blocked. To ask the user something, end your turn with the question as " +
        "your final message — ask only what you cannot work out from the workspace or your tools, " +
        "and batch related questions into one message instead of asking them one at a time."

private fun textMessage(role: String, text: String): ChatMessage =
    ChatMessage(role = role, content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)))

internal fun userMessage(text: String): ChatMessage = textMessage(ROLE_USER, text)

internal fun toolResultMessage(callId: String, text: String): ChatMessage =
    ChatMessage(
        role = ROLE_TOOL,
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
        toolCallId = callId,
    )

private val ToolResult.outcome: AgentEvent.ToolCallFinished.Outcome
    get() = when (this) {
        is ToolResult.Success -> AgentEvent.ToolCallFinished.Outcome.SUCCESS
        is ToolResult.Error -> AgentEvent.ToolCallFinished.Outcome.ERROR
        is ToolResult.TimedOut -> AgentEvent.ToolCallFinished.Outcome.TIMED_OUT
    }

private const val ROLE_SYSTEM: String = "system"
private const val ROLE_USER: String = "user"
private const val ROLE_TOOL: String = "tool"

private const val FINISH_REASON_ERROR: String = "error"
