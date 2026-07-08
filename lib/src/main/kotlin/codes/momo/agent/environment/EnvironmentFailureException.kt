package codes.momo.agent.environment

/**
 * The environment broke — its container died or the Docker daemon became
 * unreachable mid-run, or the run's results could not be recovered on
 * close. A run-level failure, never the result of a command that merely
 * failed.
 */
public class EnvironmentFailureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
