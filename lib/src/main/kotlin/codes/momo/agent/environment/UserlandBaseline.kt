package codes.momo.agent.environment

/** Userland baseline every environment must provide (see the README's platform section). */
internal val USERLAND_BASELINE: List<String> =
    listOf("bash", "cat", "cp", "find", "grep", "ls", "mkdir", "mv", "rm", "sed")
