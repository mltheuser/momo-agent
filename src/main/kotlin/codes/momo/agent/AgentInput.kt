package codes.momo.agent

/** One [Agent.send] input: everything the outside world can feed a session. */
public sealed interface AgentInput {

    /**
     * Starts a new prompt; [text] is the verbatim user message.
     *
     * @throws IllegalArgumentException when [text] is blank.
     */
    public data class UserMessage(val text: String) : AgentInput {

        init {
            require(text.isNotBlank()) { "A user message must not be blank." }
        }
    }

    /**
     * Answers the pending question; [text] is delivered verbatim as the
     * asking call's tool result, size-bounded like a dispatched result.
     *
     * @throws IllegalArgumentException when [text] is blank.
     */
    public data class Answer(val text: String) : AgentInput {

        init {
            require(text.isNotBlank()) { "An answer must not be blank." }
        }
    }
}
