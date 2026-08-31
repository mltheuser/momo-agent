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

    // The run-failure error log; binding a provider stays with the embedder.
    implementation(libs.slf4j.api)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlin.test)
    // The live tier hands the SDK its own Ktor client, so a wedged completion
    // fails in seconds instead of the SDK default's ten minutes.
    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.cio)
    testFixturesImplementation(libs.ktor.client.content.negotiation)
    testFixturesImplementation(libs.ktor.serialization.kotlinx.json)
    // The mocked tier hands the SDK a client whose engine is the fake router,
    // so a scripted reply never crosses a socket.
    testFixturesImplementation(libs.ktor.client.mock)
}

kotlin {
    // Give the fixtures and the live tests `internal` access to main.
    val associated = setOf("testFixtures", "liveTest")
    target.compilations.matching { it.name in associated }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
}

// Resolved once for the whole build — see the root build script.
val aiRouterBaseUrl: String by rootProject.extra
val aiRouterChatModel: String by rootProject.extra

// A per-test ceiling orders of magnitude above what a tier legitimately
// takes: JUnit enforces none of its own, so anything deadlocking below the
// suites' own bounded waits would stall the test JVM with no output at all.
// The thread mode is what makes it preemptive — a timeout enforced on the
// test's own thread is only reported once the test returns, which is never.
fun Test.hangBackstop(minutes: Int) {
    systemProperty("junit.jupiter.execution.timeout.default", "${minutes}m")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
}

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
                    outputs.upToDateWhen { false } // always re-run: never UP-TO-DATE
                    // Mocked and unit work is milliseconds, its own waits seconds.
                    hangBackstop(minutes = 2)
                }
            }
        }

        // The suite driving a real backend: it talks to a running ai-router,
        // so it carries its coordinates.
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
                    systemProperty("aiRouter.baseUrl", aiRouterBaseUrl)
                    systemProperty("aiRouter.chatModel", aiRouterChatModel)
                    // Disk fixtures resolve against the module folder, not the working directory.
                    systemProperty("momo.examplesDir", layout.projectDirectory.dir("examples").asFile.absolutePath)
                }
            }
        }

        // The live tier is the primary signal, so it runs in `check` — and with
        // it `build` acquires everything that tier needs (TESTING.md).
        register<JvmTestSuite>("liveTest") {
            integrationSuite()
            targets.all {
                testTask.configure {
                    description = "Runs the live integration tests against a running local ai-router."
                    // Real model latency, and a case may chain two agent runs,
                    // each carrying the fixtures' five-minute wall clock.
                    hangBackstop(minutes = 15)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("liveTest"))
}
