package codes.momo.agent

/** Base URL of the running ai-router the live tests talk to. */
internal val liveBaseUrl: String
    get() = requiredSystemProperty("aiRouter.baseUrl")

/** Model the live tests converse with. */
internal val liveChatModel: String
    get() = requiredSystemProperty("aiRouter.chatModel")

/** The [RunSettings] the live tests prompt runs with. */
internal val liveRunSettings: RunSettings
    get() = RunSettings(model = liveChatModel)

/** Absolute path of the module's `examples/` folder holding the reference harnesses. */
internal val examplesDir: String
    get() = requiredSystemProperty("momo.examplesDir")

/**
 * Live-test configuration arrives as system properties set by the
 * `liveTest` Gradle task (see build.gradle.kts).
 */
private fun requiredSystemProperty(name: String): String =
    checkNotNull(System.getProperty(name)) {
        "System property '$name' is not set — run via ./gradlew liveTest, " +
            "or set -DaiRouter.baseUrl=... / -DaiRouter.chatModel=..."
    }
