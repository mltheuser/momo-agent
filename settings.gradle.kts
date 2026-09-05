rootProject.name = "momo-agent"

include("lib")
include("server")

val sdkDir = File("${System.getProperty("user.home")}/Develop/Private/ai-router/SDKs/kotlin")

if (!sdkDir.isDirectory || !(File(sdkDir, "build.gradle.kts").isFile || File(sdkDir, "build.gradle").isFile)) {
    throw GradleException(
        "ai-router SDK checkout not found at: $sdkDir — " +
            "clone https://github.com/mltheuser/ai-router there or edit the path in settings.gradle.kts."
    )
}

includeBuild(sdkDir)
