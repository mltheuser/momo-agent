rootProject.name = "momo-agent"

include("lib")
include("server")

// The only LLM backend is the local ai-router project, consumed through its
// Kotlin SDK as a Gradle composite build. Path hardcoded for now — revisit when needed.
val sdkDir = File("${System.getProperty("user.home")}/Develop/Private/ai-router/SDKs/kotlin")

if (!sdkDir.isDirectory || !(File(sdkDir, "build.gradle.kts").isFile || File(sdkDir, "build.gradle").isFile)) {
    throw GradleException(
        "ai-router SDK checkout not found at: $sdkDir — " +
            "clone https://github.com/mltheuser/ai-router there or edit the path in settings.gradle.kts. " +
            "The checkout is all this build needs to configure; what a green `build` needs on top of it " +
            "is in docs/testing.md, What a fresh checkout needs."
    )
}

includeBuild(sdkDir)
