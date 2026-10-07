package codes.momo.agent

import ai.router.sdk.chat.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
public data class ChatSelection(val model: String, val reasoningEffort: ReasoningEffort)

/** What a session's next prompt runs with, as far as chosen: a null [chat] is unset, a missing tool entry too. */
@Serializable
public data class SessionSelection(val chat: ChatSelection?, val toolModels: Map<String, String>) {

    public fun mergedWith(patch: SelectionPatch): SessionSelection =
        SessionSelection(patch.chat ?: chat, toolModels + patch.toolModels)

    /** The selection as a run's settings; null while [chat] is unset. */
    public fun runSettings(): RunSettings? = chat?.let { RunSettings(it.model, it.reasoningEffort, toolModels) }

    public companion object {

        public val NONE: SessionSelection = SessionSelection(chat = null, toolModels = emptyMap())
    }
}

/** A change to a [SessionSelection]: an absent [chat] or tool entry leaves that slot as it is. */
@Serializable
public data class SelectionPatch(
    val chat: ChatSelection? = null,
    val toolModels: Map<String, String> = emptyMap(),
) {

    public val isEmpty: Boolean
        get() = chat == null && toolModels.isEmpty()

    public companion object {

        public fun of(settings: RunSettings): SelectionPatch =
            SelectionPatch(ChatSelection(settings.model, settings.reasoningEffort), settings.toolModels)
    }
}

/** The selection a log records: its spawn-time settings, then every pick and every run's settings merged in order. */
public fun List<AgentEvent>.selection(): SessionSelection = fold(SessionSelection.NONE, SessionSelection::after)

internal fun SessionSelection.after(event: AgentEvent): SessionSelection = when (event) {
    is AgentEvent.SessionStarted -> event.settings?.let { mergedWith(SelectionPatch.of(it)) } ?: this
    is AgentEvent.SelectionChanged -> mergedWith(event.patch)
    is AgentEvent.RunStarted -> mergedWith(SelectionPatch.of(event.settings))
    else -> this
}
