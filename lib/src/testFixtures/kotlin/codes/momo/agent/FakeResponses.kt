package codes.momo.agent

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ModelInfo
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ProviderType
import ai.router.sdk.models.ReasoningEffort
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TEST_MODEL = "test-model"

/** The [RunSettings] the mocked tier runs under, matching the model its responses report. */
public val TEST_RUN_SETTINGS: RunSettings = RunSettings(model = TEST_MODEL)

/** A chat response with no tool calls. */
public fun assistantResponse(finishReason: String, text: String = ""): ChatResponse = ChatResponse(
    model = TEST_MODEL,
    message = ChatMessage(
        role = "assistant",
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
    ),
    finishReason = finishReason,
    usage = RESPONSE_USAGE,
)

/** A chat response asking for [calls]. */
public fun toolCallResponse(vararg calls: ToolCall): ChatResponse = ChatResponse(
    model = TEST_MODEL,
    message = ChatMessage(role = "assistant", content = emptyList(), toolCalls = calls.toList()),
    finishReason = "tool_calls",
    usage = RESPONSE_USAGE,
)

public fun bashCall(id: String, command: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(name = "bash", arguments = buildJsonObject { put("command", command) }),
)

public fun viewImageCall(id: String, path: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(name = "view_image", arguments = buildJsonObject { put("path", path) }),
)

public fun spawnSubagentCall(
    id: String,
    name: String,
    type: String = "self",
    modelId: String? = null,
    reasoningEffort: ReasoningEffort? = null,
): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(
        name = "spawn_subagent",
        arguments = buildJsonObject {
            put("name", name)
            put("type", type)
            modelId?.let { put("model_id", it) }
            reasoningEffort?.let {
                put("reasoning_effort", aiRouterSdkJson.encodeToJsonElement(ReasoningEffort.serializer(), it))
            }
        },
    ),
)

/**
 * A catalog of chat+tools models under the given fully-qualified
 * `id:tag@provider` strings — the shape spawn-time model validation
 * accepts, each entry's fields derived from its string.
 */
public fun usableCatalog(vararg models: String): ModelList = ModelList(
    `object` = "list",
    data = models.map { model ->
        val provider = model.substringAfterLast('@')
        val tagged = model.substringBeforeLast('@')
        ModelInfo(
            id = tagged.substringBeforeLast(':'),
            model = model,
            provider = provider,
            providerType = if (tagged.substringAfterLast(':') == "local") ProviderType.LOCAL else ProviderType.CLOUD,
            capabilities = listOf(Capability.CHAT, Capability.TOOLS),
        )
    },
)

public fun promptSubagentCall(id: String, name: String, message: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(
        name = "prompt_subagent",
        arguments = buildJsonObject {
            put("name", name)
            put("message", message)
        },
    ),
)

/** The usage every response of the mocked tier reports. */
public val RESPONSE_USAGE: ChatUsage = ChatUsage(
    promptTokens = 1,
    completionTokens = 1,
    totalTokens = 2,
    reasoningTokens = 0,
    cacheReadTokens = 0,
)
