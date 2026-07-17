package codes.momo.agent

/**
 * Signals a dormant subagent that cannot be revived under the current
 * harness configuration: its stored spawn carries no type (a log predating
 * typed spawning) or a type the parent's harness no longer declares.
 */
public class SubagentRevivalException(message: String) : RuntimeException(message)
