package codes.momo.agent

import ai.router.sdk.models.ChatResponse
import java.util.concurrent.CountDownLatch

/** One [scriptedServer] turn: a chat completion, a failing HTTP status with an API error body, or a raw body. */
public sealed interface ScriptedReply {

    public data class Success(val response: ChatResponse) : ScriptedReply

    public data class Failure(val statusCode: Int, val message: String) : ScriptedReply

    /** A verbatim JSON body served with [statusCode], for scripting endpoints beyond chat completions. */
    public data class Raw(val statusCode: Int, val body: String) : ScriptedReply

    /** A completion withheld until [release]: its request stays in flight, keeping the run active. */
    public class Held(private val response: ChatResponse) : ScriptedReply {

        private val latch = CountDownLatch(1)

        public fun release() {
            latch.countDown()
        }

        internal fun awaitRelease(): ChatResponse {
            latch.await()
            return response
        }
    }
}

/** Shorthand wrapping a response as a [scriptedServer] turn. */
public fun ChatResponse.asReply(): ScriptedReply = ScriptedReply.Success(this)

/** A failure turn with a status the retry policy classifies as transient. */
public fun transientFailure(message: String): ScriptedReply =
    ScriptedReply.Failure(statusCode = 503, message = message)
