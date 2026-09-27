rootProject.name = "momo-agent"

include("lib")
include("server")

val sdkDir = settingsDir.resolve("../ai-router/SDKs/kotlin").canonicalFile

if (!sdkDir.isDirectory || !(File(sdkDir, "build.gradle.kts").isFile || File(sdkDir, "build.gradle").isFile)) {
    throw GradleException(
        "ai-router SDK checkout not found at: $sdkDir — " +
            "clone https://github.com/mltheuser/ai-router beside momo-agent (../ai-router)."
    )
}

includeBuild(sdkDir)
