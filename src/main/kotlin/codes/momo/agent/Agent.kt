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
import codes.momo.agent.tool.coreToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

/**
 * One agent session over a loaded [Harness]: [prompt] runs the LLM/tool
 * loop against the session's accumulated conversation, under [RunBudgets].
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
    internal val userChannel: UserChannel,
    internal val eventListener: AgentEventListener,
    private val budgets: RunBudgets,
) {

    public constructor(
        harness: Harness,
        client: AiRouterClient,
        environment: ExecutionEnvironment,
        userChannel: UserChannel,
        eventListener: AgentEventListener = NoOpAgentEventListener,
    ) : this(harness, client, environment, userChannel, eventListener, RunBudgets())

    private val registry: ToolRegistry = coreToolRegistry()

    private val toolDefinitions: List<ToolDefinition>

    init {
        harness.requireToolsKnown(registry.names)
        toolDefinitions = registry.definitions(harness.tools)
    }

    private val running = AtomicBoolean(false)

    private val history: MutableList<ChatMessage> =
        mutableListOf(textMessage(ROLE_SYSTEM, systemPromptFor(harness, environment.workspacePath)))

    /**
     * Sends [text] as the next user message and runs the loop until the
     * model answers without tool calls or a budget ends the run: each turn
     * is one LLM call, followed by executing every requested tool call
     * sequentially, in order. Budget breaches and terminal LLM failures are
     * reported through the returned [PromptResult], never thrown.
     *
     * Calls accumulate: each continues the previous conversation with fresh
     * budget counters. After every outcome — cancellation included — the
     * stored conversation stays well-formed for the next call: tool calls
     * the run never finished are answered with synthesized aborted results.
     */
    public suspend fun prompt(text: String): PromptResult {
        check(running.compareAndSet(false, true)) {
            "prompt() is already running on this agent — await the active call before sending another."
        }
        try {
            return runPrompt(text)
        } finally {
            running.set(false)
        }
    }

    private suspend fun runPrompt(text: String): PromptResult {
        val start = TimeSource.Monotonic.markNow()
        val run = RunState()
        history += textMessage(ROLE_USER, text)
        val status = try {
            withTimeoutOrNull(budgets.maxWallClock) { runLoop(run) } ?: PromptResult.Status.TIMEOUT
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            run.failure = failure
            PromptResult.Status.ERROR
        } finally {
            // A finally so the repair also runs on the abrupt exits
            // (timeout cancellation, external cancellation).
            history += abortedToolResults(history)
        }
        return PromptResult(
            status = status,
            // The wall-clock timer can fire between the loop recording its
            // final message and delivering COMPLETED; the timeout must not
            // leak that message.
            finalMessage = run.finalMessage.takeIf { status == PromptResult.Status.COMPLETED },
            transcript = history.toList(),
            usage = run.usage,
            turnsUsed = run.turnsUsed,
            elapsed = start.elapsedNow(),
            error = run.failure,
        )
    }

    private suspend fun runLoop(run: RunState): PromptResult.Status {
        while (true) {
            val response = takeTurn(run)
            val outcome = turnOutcome(run, response)
            if (outcome != null) {
                return outcome
            }
            executeToolCalls(response.message.toolCalls.orEmpty())
        }
    }

    /** The run-ending status this turn produced, or null when the loop continues with its tool calls. */
    private fun turnOutcome(run: RunState, response: ChatResponse): PromptResult.Status? {
        val toolCalls = response.message.toolCalls.orEmpty()
        return when {
            response.finishReason == FINISH_REASON_ERROR -> {
                // A provider-side failure can arrive as a successful HTTP
                // response; its text must not read as the model's answer.
                run.failure = IllegalStateException("the LLM reported a failed response (finish_reason 'error').")
                PromptResult.Status.ERROR
            }

            toolCalls.isEmpty() -> {
                run.finalMessage = response.textContent
                PromptResult.Status.COMPLETED
            }

            // The pending calls stay unexecuted: no LLM call is left to
            // ever see their results.
            run.turnsUsed >= budgets.maxTurns -> PromptResult.Status.TURNS_EXHAUSTED

            else -> null
        }
    }

    /** One turn: one successful LLM call — retries cost wall-clock, not turns. */
    private suspend fun takeTurn(run: RunState): ChatResponse {
        val request = ChatRequest(model = harness.model, messages = history.toList(), tools = toolDefinitions)
        val response = retryTransientFailures { client.chat(request) }
        run.turnsUsed++
        run.usage += response.usage
        history += response.message
        return response
    }

    private suspend fun executeToolCalls(toolCalls: List<ToolCall>) {
        for (call in toolCalls) {
            val result = registry.execute(call.function.name, call.function.arguments, environment)
            history += toolResultMessage(call.id, result.text)
        }
    }

    /** Mutable accounting for one [prompt] run. */
    private class RunState {
        var turnsUsed: Int = 0
        var usage: ChatUsage = ZERO_USAGE
        var finalMessage: String? = null
        var failure: Exception? = null
    }
}

/** The system prompt: the harness instructions plus the library-owned workspace facts. */
internal fun systemPromptFor(harness: Harness, workspacePath: String): String =
    harness.instructions.trimEnd() +
        "\n\nThe workspace root is $workspacePath. File paths passed to tools must be absolute."

private fun textMessage(role: String, text: String): ChatMessage =
    ChatMessage(role = role, content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)))

internal fun toolResultMessage(callId: String, text: String): ChatMessage =
    ChatMessage(
        role = ROLE_TOOL,
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
        toolCallId = callId,
    )

private const val ROLE_SYSTEM: String = "system"
private const val ROLE_USER: String = "user"
private const val ROLE_TOOL: String = "tool"

private const val FINISH_REASON_ERROR: String = "error"
