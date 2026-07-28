plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
    // Fixtures shared by every test suite here: the HTTP shape of the API.
    `java-test-fixtures`
    `jvm-test-suite`
}

dependencies {
    implementation(project(":lib"))

    // The Ktor version (catalog) matches the major version the ai-router SDK's client uses.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.core)

    // The fixtures drive the API as a client; main is only ever the server.
    // Its own `implementation` dependencies do not reach an associated
    // compilation, so the fixtures name what they use.
    testFixturesImplementation(project(":lib"))
    testFixturesImplementation(libs.ktor.serialization.kotlinx.json)
    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.cio)
    testFixturesImplementation(libs.ktor.client.content.negotiation)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlin.test)
}

application {
    mainClass = "codes.momo.agent.server.MainKt"
}

kotlin {
    // The API's request and response types are `internal`, so everything
    // speaking to it needs `internal` access to main — the fixtures included.
    target.compilations.matching { it.name in setOf("testFixtures", "liveTest", "containerTest") }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
    // What the live suite shares with the default one is the client side of
    // the API — the fixtures. The server behind it is deliberately not
    // shared: the live suite drives the packaged distribution as a process,
    // which is the whole point of the tier.
    target.compilations.matching { it.name in setOf("test", "liveTest", "containerTest") }.configureEach {
        associateWith(target.compilations.getByName("testFixtures"))
    }
    // The container tests also reach the default suite's server harnesses.
    target.compilations.matching { it.name == "containerTest" }.configureEach {
        associateWith(target.compilations.getByName("test"))
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
        // The default suite runs the real routing and serialization over a
        // loopback socket, against a client whose engine is the fake router.
        val serverTestDependencies: JvmComponentDependencies.() -> Unit = {
            implementation(testFixtures(project(":lib")))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.test)
        }

        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies { serverTestDependencies() }
            targets.all {
                testTask.configure {
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    outputs.upToDateWhen { false } // always re-run: never UP-TO-DATE
                    // Mocked and unit work is milliseconds, its own waits seconds.
                    hangBackstop(minutes = 2)
                }
            }
        }

        // The live suite drives the installed distribution as a real OS
        // process over real HTTP — Main.kt included — against a running
        // ai-router, and runs in `check` (TESTING.md).
        register<JvmTestSuite>("liveTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(":lib"))
                implementation(testFixtures(project(":lib")))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlin.test)
            }
            targets.all {
                testTask.configure {
                    description = "Runs the live server tests against a real server process and a local ai-router."
                    // One router and one provider key serve both live suites;
                    // run in parallel they contend for it, and contention reads
                    // as a wedged generation rather than as load.
                    mustRunAfter(":lib:liveTest")
                    // The suite launches the start script the distribution installs.
                    dependsOn(tasks.installDist)
                    systemProperty(
                        "momo.serverBin",
                        layout.buildDirectory.file("install/server/bin/server").get().asFile.absolutePath,
                    )
                    systemProperty("aiRouter.baseUrl", aiRouterBaseUrl)
                    systemProperty("aiRouter.chatModel", aiRouterChatModel)
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    // Always re-run against the live backend: never UP-TO-DATE and
                    // never restored FROM-CACHE (Test tasks are @CacheableTask).
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                    // A server process to start plus real model latency, and a
                    // case may chain several of the suite's own 90-second waits.
                    hangBackstop(minutes = 15)
                }
            }
        }

        // Deliberately NOT wired into `check`, mirroring the lib module:
        // `./gradlew build` must succeed without a Docker daemon.
        register<JvmTestSuite>("containerTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                serverTestDependencies()
            }
            targets.all {
                testTask.configure {
                    description = "Runs the container session tests against a local Docker daemon."
                    // Both container suites assert over the shared codes.momo.agent
                    // container label; concurrent runs would see each other's containers.
                    mustRunAfter(":lib:containerTest")
                    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    // Always re-run against the live backend: never UP-TO-DATE and
                    // never restored FROM-CACHE (Test tasks are @CacheableTask).
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                    // Container startup on top of an image pull on a cold daemon.
                    hangBackstop(minutes = 20)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("liveTest"))
}
