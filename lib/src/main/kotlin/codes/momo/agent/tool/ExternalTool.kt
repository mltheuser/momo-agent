package codes.momo.agent.tool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * A tool answered from outside the process: nothing executes in-process —
 * the agent loop parks the prompt on its calls
 * ([codes.momo.agent.PromptResult.Status.AWAITING_USER]) until the answer
 * arrives as the call's tool result.
 */
public abstract class ExternalTool<A : Any> internal constructor(
    name: String,
    description: String,
    argsSerializer: KSerializer<A>,
) : Tool<A>(name, description, argsSerializer) {

    /** The user-facing question the validated [args] pose. */
    internal abstract fun question(args: A): String

    /** The question [arguments] pose, or null when they do not decode. */
    internal fun questionOrNull(arguments: JsonObject): String? {
        val args = try {
            decode(arguments)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return null
        }
        return question(args)
    }
}
