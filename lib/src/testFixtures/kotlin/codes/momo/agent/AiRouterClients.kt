package codes.momo.agent

import ai.router.sdk.AiRouterClient

/**
 * A client for code paths that never reach the LLM; the caller owns closing
 * it. Port 9 is the discard service, reserved and served by nothing, so a
 * call that should never happen is refused at once rather than answered by
 * whatever else happens to be listening.
 */
public fun unusedAiRouterClient(): AiRouterClient = AiRouterClient("http://127.0.0.1:9")
