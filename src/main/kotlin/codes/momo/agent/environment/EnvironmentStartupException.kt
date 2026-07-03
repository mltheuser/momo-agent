package codes.momo.agent.environment

public class EnvironmentStartupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
