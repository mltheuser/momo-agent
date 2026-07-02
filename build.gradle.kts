plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    `jvm-test-suite`
}

group = "codes.momo"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Substituted with the local checkout via the composite build (settings.gradle.kts).
    implementation("ai.router:ai-router-sdk:0.1.0")

    // YAML parsing for harness.yaml manifests.
    implementation("com.charleskorn.kaml:kaml:0.83.0")

    // Lint formatting rules (folds ktlint into detekt — no separate ktlint plugin)
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

kotlin {
    jvmToolchain(21)
    // Require an explicit visibility modifier and explicit return type on every
    // public/protected declaration — keeps the published API surface intentional.
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Strict
    compilerOptions {
        allWarningsAsErrors = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    // Scan all source sets, including the live-integration-test sources.
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/liveTest/kotlin"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Fail the build on any detekt finding.
    ignoreFailures = false
}

// ─── Live integration tests ───────────────────────────────────────────
//
// The `liveTest` suite talks to a running local ai-router server. It is
// deliberately NOT wired into `check`: `./gradlew build` must succeed
// without a server. Run it explicitly with `./gradlew liveTest`.

// Resolve a setting from a Gradle property, then an environment variable, then
// the default. Blank/whitespace-only values (e.g. -PaiRouterBaseUrl= or an
// exported-but-empty env var) are treated as unset. Resolving via `orNull` on
// value-source providers keeps this configuration-cache safe.
fun resolveLiveTestSetting(propertyName: String, envName: String, default: String): String =
    providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
        ?: default

// NOTE: these defaults also appear in the README table — keep the two in sync
// when changing them.
val liveTestBaseUrl: String =
    resolveLiveTestSetting("aiRouterBaseUrl", "AI_ROUTER_BASE_URL", "http://localhost:8787")

val liveTestChatModel: String =
    resolveLiveTestSetting("aiRouterChatModel", "AI_ROUTER_CHAT_MODEL", "qwen3.5:9b:local@ollama")

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
            targets.all {
                testTask.configure {
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    outputs.upToDateWhen { false } // disable test result caching
                }
            }
        }

        register<JvmTestSuite>("liveTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                // Substituted with the local checkout via the composite build.
                implementation("ai.router:ai-router-sdk:0.1.0")
                // The SDK declares its deps as `implementation`, so coroutines are
                // not on our compile classpath transitively; needed for runBlocking.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
            targets.all {
                testTask.configure {
                    description = "Runs the live integration tests against a running local ai-router."
                    systemProperty("aiRouter.baseUrl", liveTestBaseUrl)
                    systemProperty("aiRouter.chatModel", liveTestChatModel)
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    // Always re-run against the live server: never UP-TO-DATE and
                    // never restored FROM-CACHE (Test tasks are @CacheableTask).
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                }
            }
        }
    }
}
