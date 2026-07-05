package codes.momo.agent

/** Base URL of the running ai-router the live tests talk to. */
internal val liveBaseUrl: String
    get() = requiredSystemProperty("aiRouter.baseUrl")

/** Model the live tests converse with. */
internal val liveChatModel: String
    get() = requiredSystemProperty("aiRouter.chatModel")

/**
 * Live-test configuration arrives as system properties set by the
 * `liveTest` Gradle task (see build.gradle.kts).
 */
private fun requiredSystemProperty(name: String): String =
    checkNotNull(System.getProperty(name)) {
        "System property '$name' is not set — run via ./gradlew liveTest, " +
            "or set -DaiRouter.baseUrl=... / -DaiRouter.chatModel=..."
    }
