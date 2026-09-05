package codes.momo.agent.environment

/** Userland baseline every environment must provide (see docs/execution-environment.md). */
internal val USERLAND_BASELINE: List<String> =
    listOf("base64", "bash", "cat", "cp", "find", "grep", "ls", "mkdir", "mv", "rm", "sed")
