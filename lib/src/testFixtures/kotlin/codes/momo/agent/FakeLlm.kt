package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ApiError
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ErrorResponse
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.harness.Harness
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A router the SDK's own client talks to below the network: [rules] answer
 * chat completions by what the request contains, [catalog] answers the model
 * listing. Every reply is built from the SDK's response types and encoded
 * with its serializers, so a breaking change to one of them fails to compile
 * here instead of passing silently.
 *
 * A rule answers every request it matches, however many arrive and in
 * whatever order; only two rules matching the same request are settled by
 * declaration order. Nothing therefore ties a reply to a request's position
 * in the run — say what the request looks like, and the reply follows it.
 *
 * A request no rule answers draws a failing status the retry policy does not
 * retry, carrying what arrived and what the script was still willing to
 * answer: a direct SDK call throws it, and an [Agent] driving the call ends
 * its run as [RunResult.Status.ERROR] with that text as its
 * [RunResult.error] — its [AgentEvent.RunFinished] emitted like any other
 * outcome, so a watcher waiting on the run's end is released at once. The
 * fake never waits, so a script that has run out or was never right cannot
 * look like a hang; and apart from [FakeLlmReply.Thrown], which exists for
 * the cases whose subject is a throwable escaping a run, it never throws past
 * the caller's error handling either.
 */
public class FakeLlm private constructor(
    private val catalog: ModelList?,
    private val rules: List<FakeLlmRule>,
) {

    public constructor(vararg rules: FakeLlmRule) : this(null, rules.toList())

    /** A fake also serving [catalog] on the model-listing endpoint. */
    public constructor(catalog: ModelList, vararg rules: FakeLlmRule) : this(catalog, rules.toList())

    private val unanswered = CopyOnWriteArrayList<String>()

    /**
     * The diagnostic of every request this fake could not answer, in arrival
     * order. A run nobody awaits — one the agent server started — reports
     * such a request only as its [RunResult.Status.ERROR] outcome, so this is
     * where the text behind that outcome stays readable.
     */
    public val unansweredRequests: List<String>
        get() = unanswered.toList()

    /** An SDK client whose every call this fake answers; the caller owns closing it. */
    public fun client(): AiRouterClient = AiRouterClient(FAKE_BASE_URL, httpClient())

    private fun httpClient(): HttpClient = HttpClient(MockEngine { request -> answer(request) }) {
        install(ContentNegotiation) {
            json(aiRouterSdkJson)
        }
    }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData) = when {
        request.url.encodedPath.endsWith(CHAT_COMPLETIONS_PATH) ->
            replyToChat(request.body.toByteArray().decodeToString())

        request.url.encodedPath.endsWith(MODELS_PATH) -> when (catalog) {
            null -> unexpected("a model listing, which this fake was not given a catalog for")
            else -> respondJson(HttpStatusCode.OK, aiRouterSdkJson.encodeToString(ModelList.serializer(), catalog))
        }

        else -> unexpected("a ${request.method.value} to ${request.url.encodedPath}")
    }

    private fun MockRequestHandleScope.replyToChat(body: String) =
        runCatching { aiRouterSdkJson.decodeFromString(ChatRequest.serializer(), body) }.fold(
            onSuccess = { reply(it) },
            onFailure = { unexpected("a body that is no ChatRequest (${it.message}):\n$body") },
        )

    private fun MockRequestHandleScope.reply(request: ChatRequest) =
        when (val reply = rules.firstNotNullOfOrNull { it.claim(request) }) {
            null -> unexpected(describe(request))

            is FakeLlmReply.Completion -> respondJson(
                HttpStatusCode.OK,
                aiRouterSdkJson.encodeToString(ChatResponse.serializer(), reply.response),
            )

            is FakeLlmReply.Failure ->
                respondApiError(HttpStatusCode.fromValue(reply.statusCode), reply.message)

            is FakeLlmReply.Verbatim -> respondJson(HttpStatusCode.fromValue(reply.statusCode), reply.body)

            is FakeLlmReply.Thrown -> throw reply.raise()
        }

    /**
     * The answer to a request the script cannot serve, naming what turned up
     * and what the rules were still willing to answer. Its status is outside
     * the transient set on purpose: a retried one would be answered three
     * more times, turning a script that ran out into a wait after all.
     */
    private fun MockRequestHandleScope.unexpected(arrived: String): HttpResponseData {
        val diagnostic = "The fake router has no reply for $arrived.\n" +
            "Its rules: " + (if (rules.isEmpty()) "(none)" else rules.joinToString("\n  ", prefix = "\n  "))
        unanswered += diagnostic
        return respondApiError(HttpStatusCode.BadRequest, diagnostic)
    }
}

/**
 * One reply of a [FakeLlm] script, and the request shape it answers.
 * [expectation] describes that shape in the failure an unanswerable request
 * produces; [uses] caps how many requests it may answer, unbounded when null.
 */
public class FakeLlmRule internal constructor(
    private val expectation: String,
    private val uses: Int?,
    private val matches: (ChatRequest) -> Boolean,
    private val reply: FakeLlmReply,
) {

    private val used = AtomicInteger()

    /** This rule's reply when it answers [request], or null when it does not — or no longer may. */
    internal fun claim(request: ChatRequest): FakeLlmReply? = when {
        !matches(request) -> null
        uses != null && !claimAllowance(uses) -> null
        else -> reply
    }

    /** Narrowed copy: [expectation] gains [detail] and the request must also satisfy [also]. */
    internal fun narrowedTo(detail: String, also: (ChatRequest) -> Boolean): FakeLlmRule =
        FakeLlmRule("$expectation, $detail", uses, { matches(it) && also(it) }, reply)

    override fun toString(): String =
        expectation + (uses?.let { " (at most $it request(s), ${used.get()} so far)" } ?: "")

    /**
     * Takes one of [cap] allowances, reporting whether there was one left.
     * The counter stops at the cap so an exhausted rule keeps reporting the
     * count it actually served — the number that explains the exhaustion.
     */
    private fun claimAllowance(cap: Int): Boolean =
        used.getAndUpdate { served -> if (served < cap) served + 1 else served } < cap
}

/** What a [FakeLlmRule] serves: a completion, a failing status with an API error body, a verbatim body, or a throw. */
public sealed interface FakeLlmReply {

    public data class Completion(val response: ChatResponse) : FakeLlmReply

    public data class Failure(val statusCode: Int, val message: String) : FakeLlmReply

    /** A body the SDK's types cannot produce — for the cases that are about a malformed response. */
    public data class Verbatim(val statusCode: Int, val body: String) : FakeLlmReply

    /**
     * No answer at all: [raise] produces the throwable served where the reply
     * would be, so it surfaces at the SDK call the run is making. A factory
     * rather than an instance, so a rule serving several requests never hands
     * out the same throwable twice.
     */
    public class Thrown(public val raise: () -> Throwable) : FakeLlmReply
}

// ─── Rules ────────────────────────────────────────────────────────────

/**
 * Answers a request whose newest message is the user's — an agent's opening
 * turn — narrowed to one containing [saying] when given.
 */
public fun onOpeningTurn(response: ChatResponse, saying: String = ""): FakeLlmRule = FakeLlmRule(
    expectation = "an opening turn" + saying.quotedOrEmpty(" whose user message says"),
    uses = null,
    matches = { it.newest.role == "user" && saying in it.newest.text },
    reply = FakeLlmReply.Completion(response),
)

/**
 * Answers a request whose newest message is a tool result — a turn after a
 * tool batch — narrowed to one containing [saying] when given.
 */
public fun onToolResults(response: ChatResponse, saying: String = ""): FakeLlmRule = FakeLlmRule(
    expectation = "a turn after tool results" + saying.quotedOrEmpty(" whose newest result says"),
    uses = null,
    matches = { it.newest.role == "tool" && saying in it.newest.text },
    reply = FakeLlmReply.Completion(response),
)

/** Answers any chat request with [reply], described as [expectation]; at most [uses] times when capped. */
public fun onAnyTurn(reply: FakeLlmReply, expectation: String, uses: Int? = null): FakeLlmRule =
    FakeLlmRule(expectation, uses, { true }, reply)

/** A single failing turn with a status the retry policy classifies as transient. */
public fun transientFailure(message: String): FakeLlmRule = onAnyTurn(
    reply = FakeLlmReply.Failure(statusCode = HTTP_SERVICE_UNAVAILABLE, message = message),
    expectation = "one transient failure",
    uses = 1,
)

/** Narrows this rule to requests a subagent sends: only their system prompt carries the spawner contract. */
public fun FakeLlmRule.fromSubagent(): FakeLlmRule =
    narrowedTo("from a subagent") { SPAWNER_CONTRACT in it.systemPrompt }

/** Narrows this rule to requests a root-level agent sends. */
public fun FakeLlmRule.fromRootAgent(): FakeLlmRule =
    narrowedTo("from a root agent") { SPAWNER_CONTRACT !in it.systemPrompt }

/**
 * Narrows this rule to requests whose system prompt carries [instructions] —
 * which is to say, to an agent running the harness that holds them.
 */
public fun FakeLlmRule.underInstructions(instructions: String): FakeLlmRule =
    narrowedTo("running the harness instructed '${instructions.take(EXPECTATION_TEXT)}'") {
        instructions in it.systemPrompt
    }

/** Narrows this rule to requests going to [model], so a run on any other model finds no reply. */
public fun FakeLlmRule.forModel(model: String): FakeLlmRule =
    narrowedTo("for model '$model'") { it.model == model }

/** Narrows this rule to requests asking for [effort] — for none when null. */
public fun FakeLlmRule.atReasoningEffort(effort: ReasoningEffort?): FakeLlmRule =
    narrowedTo("at reasoning effort ${effort ?: "(none)"}") { it.reasoningEffort == effort }

// ─── Request views ────────────────────────────────────────────────────

/** The message the request was built around: everything before it is the conversation so far. */
public val ChatRequest.newest: ChatMessage
    get() = messages.last()

/** The system prompt the request opens with. */
public val ChatRequest.systemPrompt: String
    get() = messages.first().text

/**
 * What arrived, including the two facts the narrowings derive rather than
 * read — which side of the subagent boundary the request came from, and which
 * harness instructed it — so a root-vs-subagent mismatch reads as one.
 */
private fun describe(request: ChatRequest): String =
    "a chat request (model '${request.model}', reasoning effort ${request.reasoningEffort ?: "(none)"}, " +
        "${request.messages.size} messages, tools ${request.tools.orEmpty().map { it.name }}) " +
        "from a ${if (SPAWNER_CONTRACT in request.systemPrompt) "subagent" else "root agent"} " +
        "under the system prompt '${request.systemPrompt.take(EXPECTATION_TEXT)}', " +
        "whose newest message is a ${request.newest.role} saying " +
        "'${request.newest.text.take(EXPECTATION_TEXT)}'"

private fun String.quotedOrEmpty(prefix: String): String = if (isEmpty()) "" else "$prefix '$this'"

private fun MockRequestHandleScope.respondJson(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

/** [message] under [status] as the [ErrorResponse] body the SDK decodes a failure from. */
private fun MockRequestHandleScope.respondApiError(status: HttpStatusCode, message: String) = respondJson(
    status,
    aiRouterSdkJson.encodeToString(
        ErrorResponse.serializer(),
        ErrorResponse(ApiError(type = FAKE_ERROR_TYPE, message = message)),
    ),
)

/**
 * The sentence a subagent's system prompt carries and a root agent's does
 * not, taken from the production prompt itself rather than restated.
 */
private val SPAWNER_CONTRACT: String = run {
    val harness = Harness(tools = listOf("bash"), instructions = MARKER_INSTRUCTIONS)
    val derived = systemPromptFor(harness, subagent = true).substringAfter(MARKER_INSTRUCTIONS)
    // A blank derivation is a needle every system prompt contains, and one a
    // root agent's prompt carries too matches every request: either silently
    // inverts fromSubagent() and fromRootAgent() across the whole tier.
    require(derived.isNotBlank() && derived !in systemPromptFor(harness, subagent = false)) {
        "The subagent system prompt no longer says anything past the harness instructions that a root " +
            "agent's does not, so the fake cannot derive what tells a subagent's request from a root agent's."
    }
    derived
}

/** Instructions [SPAWNER_CONTRACT] splits a system prompt on, so nothing but the prompt's own tail survives. */
private const val MARKER_INSTRUCTIONS: String = "fixture-instructions"

/** Never dialed: the fake answers below the network, so the SDK's base URL only has to be well-formed. */
private const val FAKE_BASE_URL: String = "http://fake-router.invalid"

private const val CHAT_COMPLETIONS_PATH: String = "/v1/chat/completions"

private const val MODELS_PATH: String = "/v1/models"

private const val HTTP_SERVICE_UNAVAILABLE: Int = 503

/** [ApiError.type] of every failure this fake serves; it prefixes the SDK's exception message. */
private const val FAKE_ERROR_TYPE: String = "fake_router"

/** How much of a message's text a failure message quotes. */
private const val EXPECTATION_TEXT: Int = 120
