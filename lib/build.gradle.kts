plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    `java-library`
    // Fixtures shared by every test suite here and by the server module's tests.
    `java-test-fixtures`
    `jvm-test-suite`
}

dependencies {
    // Substituted with the local checkout via the composite build
    // (settings.gradle.kts). `api`: SDK types — and its exported
    // kotlinx-serialization types — sit in public tool signatures.
    api(libs.ai.router.sdk)

    // YAML parsing for harness.yaml manifests.
    implementation(libs.kaml)

    // Coroutine primitives for the suspend-based execution seam.
    implementation(libs.kotlinx.coroutines.core)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlin.test)
}

kotlin {
    // Give the fixtures and the live and container tests `internal` access to main.
    val associated = setOf("testFixtures", "liveTest", "containerTest")
    target.compilations.matching { it.name in associated }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
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
    resolveLiveTestSetting("aiRouterChatModel", "AI_ROUTER_CHAT_MODEL", "gemma4:31b-it-qat:local@ollama")

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(libs.kotlin.test)
                // Virtual-time coroutine testing — the tool timeout budget is minutes long.
                implementation(libs.kotlinx.coroutines.test)
            }
            targets.all {
                testTask.configure {
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    outputs.upToDateWhen { false } // disable test result caching
                }
            }
        }

        // Shared shape of the opt-in integration suites — all deliberately NOT
        // wired into `check`: `./gradlew build` must succeed without their backends.
        val integrationSuite: JvmTestSuite.() -> Unit = {
            useJUnitJupiter()
            dependencies {
                // Also carries the ai-router SDK (exported at `api` scope by main).
                implementation(project())
                implementation(testFixtures(project()))
                // The SDK declares its deps as `implementation`, so coroutines are
                // not on our compile classpath transitively; needed for runBlocking.
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlin.test)
            }
            targets.all {
                testTask.configure {
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    // Always re-run against the live backend: never UP-TO-DATE and
                    // never restored FROM-CACHE (Test tasks are @CacheableTask).
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                }
            }
        }

        register<JvmTestSuite>("liveTest") {
            integrationSuite()
            targets.all {
                testTask.configure {
                    description = "Runs the live integration tests against a running local ai-router."
                    systemProperty("aiRouter.baseUrl", liveTestBaseUrl)
                    systemProperty("aiRouter.chatModel", liveTestChatModel)
                    // Disk fixtures resolve against the module folder, not the working directory.
                    systemProperty("momo.examplesDir", layout.projectDirectory.dir("examples").asFile.absolutePath)
                }
            }
        }

        register<JvmTestSuite>("containerTest") {
            integrationSuite()
            targets.all {
                testTask.configure {
                    description = "Runs the container integration tests against a local Docker daemon."
                }
            }
        }
    }
}
