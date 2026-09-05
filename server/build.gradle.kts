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

    // The SLF4J provider the packaged server binds, configured by
    // simplelogger.properties in resources.
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "codes.momo.agent.server.MainKt"
}

kotlin {
    // The API's request and response types are `internal`, so everything
    // speaking to it needs `internal` access to main.
    target.compilations.matching { it.name in setOf("test", "liveTest") }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
}

// Resolved once for the whole build — see the root build script.
val aiRouterBaseUrl: String by rootProject.extra
val aiRouterChatModel: String by rootProject.extra

testing {
    suites {
        // The one unit test the tree keeps: the rewind cascade as pure log
        // analysis (docs/testing.md).
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project(":lib"))
                implementation(libs.kotlin.test)
            }
        }

        // The live suite — the suite — drives the installed distribution as a
        // real OS process over real HTTP, Main.kt included, against a running
        // ai-router and a real model, and runs in `check` (docs/testing.md).
        register<JvmTestSuite>("liveTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(":lib"))
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
                    // The suite launches the start script the distribution installs.
                    dependsOn(tasks.installDist)
                    systemProperty(
                        "momo.serverBin",
                        layout.buildDirectory.file("install/server/bin/server").get().asFile.absolutePath,
                    )
                    systemProperty("aiRouter.baseUrl", aiRouterBaseUrl)
                    systemProperty("aiRouter.chatModel", aiRouterChatModel)
                    // Never restored FROM-CACHE (Test tasks are @CacheableTask):
                    // every invocation hits the backend again.
                    outputs.cacheIf { false }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("liveTest"))
}
