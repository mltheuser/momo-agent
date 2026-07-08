package codes.momo.agent

import ai.router.sdk.models.ChatResponse

/** One [scriptedServer] turn: a chat completion, or a failing HTTP status with an API error body. */
public sealed interface ScriptedReply {

    public data class Success(val response: ChatResponse) : ScriptedReply

    public data class Failure(val statusCode: Int, val message: String) : ScriptedReply
}

/** Shorthand wrapping a response as a [scriptedServer] turn. */
public fun ChatResponse.asReply(): ScriptedReply = ScriptedReply.Success(this)

/** A failure turn with a status the retry policy classifies as transient. */
public fun transientFailure(message: String): ScriptedReply =
    ScriptedReply.Failure(statusCode = 503, message = message)
