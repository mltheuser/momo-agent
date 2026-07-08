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
import codes.momo.agent.tool.ExternalTool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import codes.momo.agent.tool.boundedResultText
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

    /** The current prompt's accumulated counters — the only in-memory state a pause keeps. */
    private var prompt: PromptState = session.parkedPrompt ?: PromptState()

    init {
        if (session is SessionState.Fresh) {
            emitter.emit { id, at -> AgentEvent.SessionStarted(id, at, sessionId, title) }
        }
    }

    /**
     * Advances the session with [input] — a user message starts a new
     * prompt, an answer resolves the pending question — and runs the
     * loop until the next outcome: each turn is one LLM call, followed by
     * executing every requested tool call sequentially, in order. A call
     * to an [ExternalTool] parks the prompt instead, as
     * [PromptResult.Status.AWAITING_USER]. Budget breaches and terminal
     * LLM failures are reported through the returned [PromptResult],
     * never thrown.
     *
     * Prompts accumulate: each continues the previous conversation with
     * fresh budget counters, while one prompt's pause-separated segments
     * share its counters. After every terminal outcome — cancellation
     * included — the stored conversation stays well-formed for the next
     * call: tool calls the prompt never finished are answered with
     * synthesized aborted results. A parked question is never aborted; it
     * keeps waiting for its answer.
     *
     * @throws IllegalStateException when [input] does not fit the session:
     *   a user message while a question is pending, an answer while none
     *   is, or a send racing an already active one.
     */
    public suspend fun send(input: AgentInput): PromptResult {
        check(running.compareAndSet(false, true)) {
            "send() is already running on this agent — await the active call before sending another."
        }
        try {
            return when (input) {
                is AgentInput.UserMessage -> startPrompt(input.text)
                is AgentInput.Answer -> answerQuestion(input.text)
            }
        } finally {
            running.set(false)
        }
    }

    private suspend fun startPrompt(text: String): PromptResult {
        check(unansweredToolCalls(history).isEmpty()) {
            "a question is pending — answer it with AgentInput.Answer before the next user message."
        }
        prompt = PromptState()
        history += userMessage(text)
        emitter.emit { id, at -> AgentEvent.RunStarted(id, at, text) }
        return runSegment(prompt)
    }

    private suspend fun answerQuestion(text: String): PromptResult {
        val call = checkNotNull(unansweredToolCalls(history).firstOrNull()) {
            "no question is pending — start a prompt with AgentInput.UserMessage."
        }
        val answer = text.boundedResultText()
        history += toolResultMessage(call.id, answer)
        emitter.emit { id, at -> AgentEvent.QuestionAnswered(id, at, call.id, answer) }
        return runSegment(prompt)
    }

    private suspend fun runSegment(prompt: PromptState): PromptResult {
        val segment = Segment(prompt)
        prompt.parked = false
        prompt.pendingQuestion = null
        val status = try {
            runLoop(segment)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            prompt.failure = failure
            PromptResult.Status.ERROR
        } finally {
            // A finally so the accounting and repair also run on external
            // cancellation. A parked ask is deliberately left unanswered:
            // its answer is still to come.
            prompt.activeElapsed += segment.start.elapsedNow()
            if (!prompt.parked) {
                history += abortedToolResults(history)
            }
        }
        val result = PromptResult(
            status = status,
            finalMessage = prompt.finalMessage.takeIf { status == PromptResult.Status.COMPLETED },
            pendingQuestion = prompt.pendingQuestion.takeIf { status == PromptResult.Status.AWAITING_USER },
            transcript = history.toList(),
            usage = prompt.usage,
            turnsUsed = prompt.turnsUsed,
            elapsed = prompt.activeElapsed,
            error = prompt.failure,
        )
        if (status != PromptResult.Status.AWAITING_USER) {
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
        }
        return result
    }

    /**
     * Advances the prompt until an outcome: each pass first drains the
     * trailing assistant message's unanswered tool calls — parking on an
     * external one — then, while wall clock remains, takes the next LLM
     * turn.
     */
    private suspend fun runLoop(segment: Segment): PromptResult.Status {
        var outcome: PromptResult.Status? = null
        while (outcome == null) {
            outcome = when {
                processPendingCalls(segment) -> PromptResult.Status.AWAITING_USER
                segment.remaining <= Duration.ZERO -> PromptResult.Status.TIMEOUT
                else -> turnOutcome(segment.prompt, takeTurn(segment))
            }
        }
        return outcome
    }

    /**
     * Executes the still-unanswered tool calls of the trailing assistant
     * message, in order; true when parked on an external call, leaving the
     * calls queued behind it untouched until the answer arrives.
     */
    private suspend fun processPendingCalls(segment: Segment): Boolean {
        for (call in unansweredToolCalls(history)) {
            val tool = registry.toolNamed(call.function.name)
            if (tool is ExternalTool<*>) {
                val question = tool.questionOrNull(call.function.arguments)
                if (question != null) {
                    park(segment.prompt, call.id, question)
                    return true
                }
            }
            executeCall(segment, call)
        }
        return false
    }

    /** Parking ignores the remaining budget. */
    private fun park(prompt: PromptState, callId: String, question: String) {
        prompt.pendingQuestion = question
        prompt.parked = true
        emitter.emit { id, at -> AgentEvent.QuestionAsked(id, at, callId, question) }
    }

    /** The segment-ending status this turn produced, or null when the loop continues with its tool calls. */
    private fun turnOutcome(prompt: PromptState, response: ChatResponse): PromptResult.Status? {
        val toolCalls = response.message.toolCalls.orEmpty()
        return when {
            response.finishReason == FINISH_REASON_ERROR -> {
                // A provider-side failure can arrive as a successful HTTP
                // response; its text must not read as the model's answer.
                prompt.failure = IllegalStateException("the LLM reported a failed response (finish_reason 'error').")
                PromptResult.Status.ERROR
            }

            toolCalls.isEmpty() -> {
                prompt.finalMessage = response.textContent
                PromptResult.Status.COMPLETED
            }

            // The pending calls stay unexecuted: no LLM call is left to
            // ever see their results.
            prompt.turnsUsed >= budgets.maxTurns -> PromptResult.Status.TURNS_EXHAUSTED

            else -> null
        }
    }

    /** One turn: one successful LLM call — retries cost wall-clock, not turns. */
    private suspend fun takeTurn(segment: Segment): ChatResponse {
        val prompt = segment.prompt
        val request = ChatRequest(model = harness.model, messages = history.toList(), tools = toolDefinitions)
        emitter.emit { id, at -> AgentEvent.LlmCallStarted(id, at, turn = prompt.turnsUsed + 1) }
        val response = retryTransientFailures(
            onRetry = { cause, attempt, backoff ->
                emitter.emit { id, at ->
                    AgentEvent.LlmCallRetried(id, at, cause.message ?: cause.toString(), attempt, backoff)
                }
            },
        ) { client.chat(request) }
        prompt.turnsUsed++
        prompt.usage += response.usage
        history += response.message
        emitter.emit { id, at ->
            AgentEvent.LlmCallFinished(id, at, response.message, response.usage, response.finishReason)
        }
        emitter.emit { id, at ->
            AgentEvent.BudgetUpdated(
                sequenceId = id,
                timestampMillis = at,
                turnsUsed = prompt.turnsUsed,
                turnsRemaining = budgets.maxTurns - prompt.turnsUsed,
                elapsed = segment.elapsed,
            )
        }
        return response
    }

    /** One dispatched (or registry-rejected) call. */
    private suspend fun executeCall(segment: Segment, call: ToolCall) {
        emitter.emit { id, at ->
            AgentEvent.ToolCallStarted(id, at, call.id, call.function.name, call.function.arguments)
        }
        val timeout = minOf(Budgets.TOOL_TIMEOUT, segment.remaining.coerceAtLeast(Duration.ZERO))
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

    /** One pause-separated segment of the current prompt: its counters plus this segment's clock. */
    private inner class Segment(val prompt: PromptState) {

        val start = TimeSource.Monotonic.markNow()

        /** Active wall-clock the prompt has consumed, this segment included. */
        val elapsed: Duration
            get() = prompt.activeElapsed + start.elapsedNow()

        val remaining: Duration
            get() = budgets.maxWallClock - elapsed
    }

    public companion object {

        /**
         * Reconstructs a session from its stored event log: the transcript
         * is derived from the log's verbatim payloads under the system
         * prompt [harness] produces now, tool calls the log left unanswered
         * are answered with synthesized aborted results, and new events
         * continue the log's sequence IDs. A log ending parked on a
         * question is restored still awaiting it — answerable via [send] —
         * with the prompt's turn and usage counters rebuilt from the log.
         * Loading emits nothing.
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
 * Mutable accounting for one prompt. The transcript's unanswered external
 * call is the authoritative parked state; [parked] and [pendingQuestion]
 * only report the current segment's park to its result and the
 * repair-skip.
 */
internal class PromptState(
    var turnsUsed: Int = 0,
    var usage: ChatUsage = ZERO_USAGE,
) {

    /** Active wall-clock consumed by ended segments; a restored parked prompt restarts at zero. */
    var activeElapsed: Duration = Duration.ZERO
    var parked: Boolean = false
    var pendingQuestion: String? = null
    var finalMessage: String? = null
    var failure: Exception? = null
}

/** The system prompt: the harness instructions plus the library-owned workspace facts. */
internal fun systemPromptFor(harness: Harness, workspacePath: String): String =
    harness.instructions.trimEnd() +
        "\n\nThe workspace root is $workspacePath. File paths passed to tools must be absolute."

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
