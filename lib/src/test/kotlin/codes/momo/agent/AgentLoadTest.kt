package codes.momo.agent

import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ReasoningEffort
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class AgentLoadTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun environment(): LocalExecutionEnvironment = LocalExecutionEnvironment(workspace)

    /** The event log recorded by one "first question" send against a [FakeLlm] over [rules]. */
    private fun recordedSession(
        vararg rules: FakeLlmRule = arrayOf(
            onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "echo hi"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "first answer")),
        ),
    ): List<AgentEvent> = recordedSession(RunBudgets(), listOf("first question"), rules.toList())

    /** The event log recorded by sending each of [prompts] in turn, under [budgets], against [rules]. */
    private fun recordedSession(
        budgets: RunBudgets,
        prompts: List<String>,
        rules: List<FakeLlmRule>,
        harness: Harness = TEST_HARNESS,
    ): List<AgentEvent> {
        val listener = CollectingEventListener()
        workspace.withFakeAgent(
            *rules.toTypedArray(),
            harness = harness,
            budgets = budgets,
            listener = listener,
        ) { agent ->
            prompts.forEach { agent.send(it, TEST_RUN_SETTINGS) }
        }
        return listener.events.toList()
    }

    // ─── Media round trip ─────────────────────────────────────────────

    @Test
    @DisplayName("A viewed image travels loop to log to replay: the reloaded tool message carries the image part")
    fun viewedImageSurvivesReplay() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        workspace.resolve("chart.png").writeBytes(png)
        val harness = Harness(tools = listOf("bash", "view_image"), instructions = TEST_HARNESS.instructions)
        val logged = recordedSession(
            RunBudgets(),
            listOf("look at the chart"),
            listOf(
                onOpeningTurn(toolCallResponse(viewImageCall(id = "call-1", path = "$workspace/chart.png"))),
                onToolResults(assistantResponse(finishReason = "stop", text = "a chart")),
            ),
            harness,
        )

        val finished = logged.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        val media = assertNotNull(finished.media, "the media must ride the stored event")
        assertEquals("image/png", media.mimeType)
        assertContentEquals(png, Base64.getDecoder().decode(media.base64Data))
        assertEquals("[image: image/png]", finished.resultText)

        FakeLlm(onToolResults(assistantResponse(finishReason = "stop", text = "still a chart"))).client()
            .use { client ->
                val agent = Agent.load(logged, harness, client, environment())
                val result = runBlocking { agent.send("look again", TEST_RUN_SETTINGS) }

                val imagePart = result.transcript
                    .single { it.role == "tool" && it.toolCallId == "call-1" }
                    .content.single()
                assertEquals(ContentPartType.IMAGE, imagePart.type)
                assertEquals(media.mimeType, imagePart.mimeType)
                assertEquals(media.base64Data, imagePart.base64Data)
            }
    }

    @Test
    @DisplayName("A prompt-attached image travels loop to log to replay: the reloaded user message carries it")
    fun promptAttachmentSurvivesReplay() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        workspace.resolve("shot.png").writeBytes(png)
        val prompt = "What is in ![shot](shot.png) here?"
        val logged = recordedSession(
            RunBudgets(),
            listOf(prompt),
            listOf(onOpeningTurn(assistantResponse(finishReason = "stop", text = "a screenshot"))),
        )

        val started = logged.filterIsInstance<AgentEvent.RunStarted>().single()
        assertEquals(prompt, started.userMessage, "the prompt string stays in the log verbatim")
        val attachment = started.attachments.single()
        assertEquals("shot.png", attachment.link)
        assertEquals("image/png", attachment.mimeType)
        assertContentEquals(png, Base64.getDecoder().decode(attachment.base64Data))

        workspace.resolve("shot.png").deleteExisting()
        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "still a screenshot"), saying = "again"))
            .client().use { client ->
                val agent = Agent.load(logged, TEST_HARNESS, client, environment())
                val result = runBlocking { agent.send("look again", TEST_RUN_SETTINGS) }

                val parts = result.transcript.first { it.role == "user" }.content
                assertEquals(
                    listOf(ContentPartType.TEXT, ContentPartType.IMAGE, ContentPartType.TEXT),
                    parts.map { it.type },
                    "replay rebuilds the multi-part message from the log alone — the file is gone",
                )
                assertEquals("What is in ![shot](shot.png)", parts[0].text)
                assertEquals(attachment.base64Data, parts[1].base64Data)
                assertEquals(" here?", parts[2].text)
            }
    }

    // ─── Tool validation ──────────────────────────────────────────────

    @Test
    @DisplayName("Loading into a harness missing a used tool fails, naming the tool")
    fun loadIntoHarnessMissingUsedToolFails() {
        val logged = recordedSession()
        val slim = Harness(tools = listOf("extra_tool"), instructions = TEST_HARNESS.instructions)

        unusedAiRouterClient().use { client ->
            val failure = assertFailsWith<HarnessValidationException> {
                Agent.load(logged, slim, client, environment())
            }
            assertContains(failure.message.orEmpty(), "bash")
        }
    }

    @Test
    @DisplayName("A hallucinated tool call the run answered with an error does not block loading")
    fun hallucinatedToolCallDoesNotBlockLoading() {
        val logged = recordedSession(
            onOpeningTurn(
                toolCallResponse(
                    ToolCall(id = "call-1", function = ToolCallFunction("teleport", buildJsonObject { })),
                ),
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "ok")),
        )

        unusedAiRouterClient().use { client ->
            Agent.load(logged, TEST_HARNESS, client, environment())
        }
    }

    // ─── Repair and log validation ────────────────────────────────────

    @Test
    @DisplayName("A log cut mid-run loads with the dangling tool call repaired")
    fun cutLogLoadsWithRepairedTranscript() {
        val logged = recordedSession()
        val cut = logged.subList(0, logged.indexOfFirst { it is AgentEvent.ToolCallStarted } + 1)

        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
            val agent = Agent.load(cut, TEST_HARNESS, client, environment())

            val result = runBlocking { agent.send("continue", TEST_RUN_SETTINGS) }

            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                result.transcript.map { it.role },
            )
            val aborted = result.transcript.single { it.role == "tool" }
            assertEquals("call-1", aborted.toolCallId)
            assertEquals(toolCallRepairText("bash", started = true, runStatus = null), aborted.text)
        }
    }

    @Test
    @DisplayName("A log rewound mid-run loads repaired, and new events number above the gap the cut left")
    fun rewoundLogLoadsRepairedAndNumbersAboveTheGap() {
        // The cut beheads the run right after its tool call started, and the
        // rewound tail is numbered above the deleted events, gap included.
        val logged = recordedSession()
        val cutPoint = logged.first { it is AgentEvent.ToolCallStarted }
        val rewound = logged.takeWhile { it.sequenceId <= cutPoint.sequenceId } +
            AgentEvent.ConversationRewound(
                sequenceId = logged.last().sequenceId + 1,
                timestampMillis = cutPoint.timestampMillis,
                lastSurvivingSequenceId = cutPoint.sequenceId,
            )

        val listener = CollectingEventListener()
        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
            val agent = Agent.load(rewound, TEST_HARNESS, client, environment(), listener)

            val result = runBlocking { agent.send("continue", TEST_RUN_SETTINGS) }

            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                result.transcript.map { it.role },
                "the beheaded run's dangling call must be repaired, the rewound tail contributing nothing",
            )
            assertEquals(
                toolCallRepairText("bash", started = true, runStatus = null),
                result.transcript.single { it.role == "tool" }.text,
            )
        }
        assertEquals(
            rewound.last().sequenceId + 1,
            listener.events.first().sequenceId,
            "new events continue above the rewound tail, never re-using a deleted sequence ID",
        )
    }

    @Test
    @DisplayName("Retrying a log cut before a failed LLM call resumes the beheaded run without a new RunStarted")
    fun retryResumesTheBeheadedRun() {
        // The failed call's tail is cut away, so the log ends at the tool
        // call the run had finished — the shape the server's retry leaves.
        val logged = recordedSession()
        val cut = logged.subList(0, logged.indexOfFirst { it is AgentEvent.ToolCallFinished } + 1)

        val listener = CollectingEventListener()
        FakeLlm(onToolResults(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
            val agent = Agent.load(cut, TEST_HARNESS, client, environment(), listener)

            val result = runBlocking { agent.retry(TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            assertEquals("recovered", result.finalMessage)
            assertEquals(
                listOf("system", "user", "assistant", "tool", "assistant"),
                result.transcript.map { it.role },
                "the retried run continues the conversation with no user message of its own",
            )
        }
        assertFalse(
            listener.events.any { it is AgentEvent.RunStarted },
            "a retry opens no run of its own in the log — it continues the beheaded one",
        )
        assertEquals(
            RunResult.Status.COMPLETED,
            (listener.events.last() as AgentEvent.RunFinished).status,
        )
    }

    @Test
    @DisplayName("Retrying a conversation that is not waiting on the model is rejected")
    fun retryOfAnAnsweredConversationIsRejected() {
        val logged = recordedSession()

        unusedAiRouterClient().use { client ->
            val agent = Agent.load(logged, TEST_HARNESS, client, environment())

            assertFailsWith<IllegalArgumentException> { runBlocking { agent.retry(TEST_RUN_SETTINGS) } }
        }
    }

    @Test
    @DisplayName("A log holding two interrupted runs loads with the dangling call of each one repaired")
    fun everyInterruptedRunInTheLogIsRepaired() {
        // A turn budget of one ends each run at the LLM boundary with its
        // requested call unexecuted, so the log carries two dangling calls —
        // one at its tail, one buried behind a later run.
        val logged = recordedSession(
            budgets = RunBudgets(maxTurns = 1),
            prompts = listOf("first question", "second question"),
            rules = listOf(
                onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "echo one")), saying = "first"),
                onOpeningTurn(toolCallResponse(bashCall(id = "call-2", command = "echo two")), saying = "second"),
            ),
        )
        assertEquals(2, logged.filterIsInstance<AgentEvent.RunStarted>().size)

        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
            val agent = Agent.load(logged, TEST_HARNESS, client, environment())

            val result = runBlocking { agent.send("continue", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant", "tool", "user", "assistant"),
                result.transcript.map { it.role },
            )
            val aborted = result.transcript.filter { it.role == "tool" }
            assertEquals(listOf("call-1", "call-2"), aborted.map { it.toolCallId })
            aborted.forEach {
                assertEquals(
                    toolCallRepairText("bash", started = false, runStatus = RunResult.Status.TURNS_EXHAUSTED),
                    it.text,
                )
            }
        }
    }

    @Test
    @DisplayName("A log not starting with SessionStarted is rejected")
    fun logWithoutSessionStartedIsRejected() {
        val logged = recordedSession()

        unusedAiRouterClient().use { client ->
            assertFailsWith<IllegalArgumentException> {
                Agent.load(emptyList(), TEST_HARNESS, client, environment())
            }
            assertFailsWith<IllegalArgumentException> {
                Agent.load(logged.drop(1), TEST_HARNESS, client, environment())
            }
        }
    }

    // ─── Serialization round trip ─────────────────────────────────────

    @Test
    @DisplayName("Every event type survives a kotlinx JSON round trip")
    fun everyEventTypeRoundTripsThroughJson() {
        val events = everyEventType()

        val decoded = Json.decodeFromString<List<AgentEvent>>(Json.encodeToString(events))

        assertEquals(events, decoded)
    }

    @Test
    @DisplayName("Every stored wire name is pinned: a rename fails here instead of orphaning stored logs")
    fun everyStoredWireNameIsPinned() {
        // The round trip above is symmetric and so blind to a rename; these
        // literals are the stored-log compatibility contract itself. Reading
        // the enums off `entries` also fails on a value added without a pin.
        val json = Json.encodeToString(everyEventType())

        assertEquals(
            listOf(
                "session_started",
                "session_renamed",
                "model_selected",
                "run_started",
                "llm_call_started",
                "llm_call_retried",
                "llm_call_finished",
                "tool_call_started",
                "tool_call_finished",
                "subagent_spawned",
                "budget_updated",
                "run_finished",
                "conversation_rewound",
                "run_resumed",
            ),
            Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            listOf("completed", "stopped", "turns_exhausted", "timeout", "error"),
            RunResult.Status.entries.map { wireName(RunResult.Status.serializer(), it) },
        )
        assertEquals(
            listOf("success", "error", "timed_out"),
            AgentEvent.ToolCallFinished.Outcome.entries
                .map { wireName(AgentEvent.ToolCallFinished.Outcome.serializer(), it) },
        )
        // Field names are the other half of it, so every event's whole key set
        // is pinned: a rename, or a field added without a decision, fails here
        // too. The discriminator owns `type`, which is why the spawn's own type
        // travels as `subagentType`.
        assertEquals(
            listOf(
                eventKeys("sessionId", "title", "depth"),
                eventKeys("title"),
                eventKeys("model", "reasoningEffort"),
                eventKeys("userMessage", "model", "reasoningEffort", "attachments"),
                eventKeys("turn"),
                eventKeys("cause", "attempt", "backoff"),
                eventKeys("message", "usage", "finishReason"),
                eventKeys("callId", "toolName", "arguments"),
                eventKeys("callId", "resultText", "outcome", "duration", "truncated", "media"),
                eventKeys("name", "sessionId", "subagentType", "modelId", "reasoningEffort"),
                eventKeys("turnsUsed", "turnsRemaining", "elapsed"),
                eventKeys("status", "finalMessage", "usage", "turnsUsed", "elapsed", "error"),
                eventKeys("lastSurvivingSequenceId"),
                eventKeys("model", "reasoningEffort"),
            ),
            Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.keys },
        )
        // The nested error and media objects are decoded field by field by
        // clients, and the pins above see only their own key, so their keys
        // are pinned too.
        assertEquals(
            setOf("message", "type", "statusCode"),
            Json.parseToJsonElement(json).jsonArray
                .single { it.jsonObject.getValue("type").jsonPrimitive.content == "run_finished" }
                .jsonObject.getValue("error").jsonObject.keys,
        )
        assertEquals(
            setOf("mimeType", "base64Data"),
            Json.parseToJsonElement(json).jsonArray
                .single { it.jsonObject.getValue("type").jsonPrimitive.content == "tool_call_finished" }
                .jsonObject.getValue("media").jsonObject.keys,
        )
        assertEquals(
            setOf("link", "mimeType", "base64Data"),
            Json.parseToJsonElement(json).jsonArray
                .single { it.jsonObject.getValue("type").jsonPrimitive.content == "run_started" }
                .jsonObject.getValue("attachments").jsonArray.single().jsonObject.keys,
        )
    }

    /** The `@SerialName` of [value], as a stored log carries it. */
    private fun <T> wireName(serializer: KSerializer<T>, value: T): String =
        Json.encodeToJsonElement(serializer, value).jsonPrimitive.content

    /** The keys every stored event carries — the discriminator and the [AgentEvent] fields — plus [own]. */
    private fun eventKeys(vararg own: String): Set<String> =
        setOf("type", "sequenceId", "timestampMillis", *own)

    /**
     * One event of every type, for the round trip and the wire-name pins.
     * Every defaulted field carries a non-default value, since a defaulted
     * one is left out of the encoding and so pins nothing.
     */
    private fun everyEventType(): List<AgentEvent> {
        val assistant = toolCallResponse(bashCall(id = "call-1", command = "echo hi"))
        val arguments = assistant.message.toolCalls!!.single().function.arguments
        return listOf(
            AgentEvent.SessionStarted(0, 1, "session-1", "Untitled", depth = 1),
            AgentEvent.SessionRenamed(1, 2, "Renamed"),
            AgentEvent.ModelSelected(2, 3, model = "picked-model", reasoningEffort = ReasoningEffort.LOW),
            AgentEvent.RunStarted(
                sequenceId = 3,
                timestampMillis = 4,
                userMessage = "question",
                model = "test-model",
                reasoningEffort = ReasoningEffort.HIGH,
                attachments = listOf(
                    AgentEvent.RunStarted.Attachment(link = "shot.png", mimeType = "image/png", base64Data = "aGk="),
                ),
            ),
            AgentEvent.LlmCallStarted(4, 5, turn = 1),
            AgentEvent.LlmCallRetried(5, 6, cause = "HTTP 503", attempt = 1, backoff = 1.seconds),
            AgentEvent.LlmCallFinished(6, 7, assistant.message, assistant.usage, "tool_calls"),
            AgentEvent.ToolCallStarted(7, 8, "call-1", "bash", arguments),
            AgentEvent.ToolCallFinished(
                sequenceId = 8,
                timestampMillis = 9,
                callId = "call-1",
                resultText = "Error: tool execution timed out after 5m.",
                outcome = AgentEvent.ToolCallFinished.Outcome.TIMED_OUT,
                duration = 5.seconds,
                truncated = true,
                media = AgentEvent.ToolCallFinished.Media(mimeType = "image/png", base64Data = "aGk="),
            ),
            AgentEvent.SubagentSpawned(
                sequenceId = 9,
                timestampMillis = 10,
                name = "helper",
                sessionId = "child-session-1",
                type = "self",
                modelId = "pinned-model",
                reasoningEffort = ReasoningEffort.LOW,
            ),
            AgentEvent.BudgetUpdated(10, 11, turnsUsed = 1, turnsRemaining = 39, elapsed = 2.seconds),
            AgentEvent.RunFinished(
                sequenceId = 11,
                timestampMillis = 12,
                status = RunResult.Status.ERROR,
                finalMessage = null,
                usage = ZERO_USAGE,
                turnsUsed = 1,
                elapsed = 3.seconds,
                error = AgentEvent.RunFinished.Error(
                    message = "fake_router: boom",
                    type = "fake_router",
                    statusCode = 503,
                ),
            ),
            AgentEvent.ConversationRewound(sequenceId = 12, timestampMillis = 13, lastSurvivingSequenceId = 4),
            AgentEvent.RunResumed(
                sequenceId = 13,
                timestampMillis = 14,
                model = "test-model",
                reasoningEffort = ReasoningEffort.LOW,
            ),
        )
    }

    @Test
    @DisplayName("A stored run_finished predating the error field still deserializes, with a null error")
    fun runFinishedWithoutErrorStillDeserializes() {
        val stored = """
            {"type":"run_finished","sequenceId":10,"timestampMillis":11,"status":"error","finalMessage":null,
            "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"reasoning_tokens":0,
            "cache_read_tokens":0},"turnsUsed":0,"elapsed":"PT1S"}
        """.trimIndent()

        val decoded = Json.decodeFromString<AgentEvent>(stored)

        assertEquals(
            AgentEvent.RunFinished(
                sequenceId = 10,
                timestampMillis = 11,
                status = RunResult.Status.ERROR,
                finalMessage = null,
                usage = ZERO_USAGE,
                turnsUsed = 0,
                elapsed = 1.seconds,
                error = null,
            ),
            decoded,
        )
    }

    @Test
    @DisplayName("A non-ERROR run_finished stores no error key, and a plain Error no type or statusCode")
    fun runFinishedOmitsAbsentErrorAndItsAbsentFields() {
        // Absent, not null: the default Json's encodeDefaults=false is what
        // keeps these keys out, and the store writes with the same instance,
        // so a flip that starts storing `"error":null` everywhere fails here.
        val errored = AgentEvent.RunFinished(
            sequenceId = 10,
            timestampMillis = 11,
            status = RunResult.Status.ERROR,
            finalMessage = null,
            usage = ZERO_USAGE,
            turnsUsed = 1,
            elapsed = 3.seconds,
            error = AgentEvent.RunFinished.Error(
                message = "fake_router: boom",
                type = "fake_router",
                statusCode = 503,
            ),
        )

        val completed = Json.encodeToJsonElement<AgentEvent>(
            errored.copy(status = RunResult.Status.COMPLETED, error = null),
        ).jsonObject
        val plain = Json.encodeToJsonElement<AgentEvent>(
            errored.copy(error = AgentEvent.RunFinished.Error(message = "boom")),
        ).jsonObject

        assertFalse("error" in completed.keys)
        assertEquals(setOf("message"), plain.getValue("error").jsonObject.keys)
    }

    @Test
    @DisplayName("A stored tool_call_finished predating the media field still deserializes, with null media")
    fun toolCallFinishedWithoutMediaStillDeserializes() {
        val stored = """
            {"type":"tool_call_finished","sequenceId":8,"timestampMillis":9,"callId":"call-1",
            "resultText":"ok","outcome":"success","duration":"PT1S","truncated":false}
        """.trimIndent()

        val decoded = Json.decodeFromString<AgentEvent>(stored)

        assertEquals(
            AgentEvent.ToolCallFinished(
                sequenceId = 8,
                timestampMillis = 9,
                callId = "call-1",
                resultText = "ok",
                outcome = AgentEvent.ToolCallFinished.Outcome.SUCCESS,
                duration = 1.seconds,
                truncated = false,
                media = null,
            ),
            decoded,
        )
    }

    @Test
    @DisplayName("A text result's tool_call_finished stores no media key at all")
    fun textToolCallFinishedOmitsMedia() {
        val event: AgentEvent = AgentEvent.ToolCallFinished(
            sequenceId = 8,
            timestampMillis = 9,
            callId = "call-1",
            resultText = "ok",
            outcome = AgentEvent.ToolCallFinished.Outcome.SUCCESS,
            duration = 1.seconds,
            truncated = false,
        )

        assertFalse("media" in Json.encodeToJsonElement(event).jsonObject.keys)
    }

    @Test
    @DisplayName("A stored run_started predating the attachments field still deserializes, with none")
    fun runStartedWithoutAttachmentsStillDeserializes() {
        val stored = """
            {"type":"run_started","sequenceId":3,"timestampMillis":4,"userMessage":"question"}
        """.trimIndent()

        val decoded = Json.decodeFromString<AgentEvent>(stored)

        assertEquals(AgentEvent.RunStarted(sequenceId = 3, timestampMillis = 4, userMessage = "question"), decoded)
    }

    @Test
    @DisplayName("A run_started without resolved images stores no attachments key at all")
    fun plainRunStartedOmitsAttachments() {
        val event: AgentEvent = AgentEvent.RunStarted(sequenceId = 3, timestampMillis = 4, userMessage = "question")

        assertFalse("attachments" in Json.encodeToJsonElement(event).jsonObject.keys)
    }

    @Test
    @DisplayName("A stored subagent_spawned predating typed spawning still deserializes, with a null type")
    fun subagentSpawnedWithoutTypeStillDeserializes() {
        val stored = """
            {"type":"subagent_spawned","sequenceId":8,"timestampMillis":9,"name":"helper","sessionId":"child-1"}
        """.trimIndent()

        val decoded = Json.decodeFromString<AgentEvent>(stored)

        assertEquals(
            AgentEvent.SubagentSpawned(
                8,
                9,
                name = "helper",
                sessionId = "child-1",
                type = null,
                modelId = null,
                reasoningEffort = null,
            ),
            decoded,
        )
    }
}
