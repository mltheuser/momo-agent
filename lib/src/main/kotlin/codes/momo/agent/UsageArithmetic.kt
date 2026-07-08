package codes.momo.agent

import ai.router.sdk.models.ChatUsage

/** Additive identity for [ChatUsage] aggregation. */
internal val ZERO_USAGE: ChatUsage = ChatUsage(
    promptTokens = 0,
    completionTokens = 0,
    totalTokens = 0,
    reasoningTokens = 0,
    cacheReadTokens = 0,
)

/** Field-wise sum, aggregating usage across a run's responses. */
internal operator fun ChatUsage.plus(other: ChatUsage): ChatUsage = ChatUsage(
    promptTokens = promptTokens + other.promptTokens,
    completionTokens = completionTokens + other.completionTokens,
    totalTokens = totalTokens + other.totalTokens,
    reasoningTokens = reasoningTokens + other.reasoningTokens,
    cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
)
