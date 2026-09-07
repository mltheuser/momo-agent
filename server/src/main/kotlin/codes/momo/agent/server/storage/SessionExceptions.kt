package codes.momo.agent.server.storage

import java.io.IOException

internal class UnknownSessionException(id: String) : RuntimeException("No such session: $id")

internal class CorruptSessionException(id: String, cause: Exception) :
    RuntimeException("Stored session $id is unreadable: ${cause.message}", cause)

internal class SessionConflictException(message: String) : RuntimeException(message)

internal class EventLogFailedException(cause: IOException) :
    RuntimeException("The session's event log failed: ${cause.message}", cause)

internal class InvalidRewindPointException(message: String) : RuntimeException(message)
