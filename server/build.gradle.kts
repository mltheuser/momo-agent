plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
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
}

application {
    mainClass = "codes.momo.agent.server.MainKt"
}

kotlin {
    // Give the container tests `internal` access to main (the default test
    // compilation is associated implicitly) and to the default suite's
    // shared helpers.
    target.compilations.matching { it.name == "containerTest" }.configureEach {
        associateWith(target.compilations.getByName("main"))
        associateWith(target.compilations.getByName("test"))
    }
}

testing {
    suites {
        // Ktor's test host runs the real routing and serialization in-process.
        val serverTestDependencies: JvmComponentDependencies.() -> Unit = {
            implementation(testFixtures(project(":lib")))
            implementation(libs.ktor.server.test.host)
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
                    outputs.upToDateWhen { false } // disable test result caching
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
                }
            }
        }
    }
}
