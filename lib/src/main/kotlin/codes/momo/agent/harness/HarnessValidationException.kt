package codes.momo.agent.harness

public class HarnessValidationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
