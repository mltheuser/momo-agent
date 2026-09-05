plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
    `jvm-test-suite`
}

dependencies {
    implementation(project(":lib"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.core)

    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "codes.momo.agent.server.MainKt"
}

kotlin {

    target.compilations.matching { it.name in setOf("test", "liveTest") }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
}

val aiRouterBaseUrl: String by rootProject.extra
val aiRouterChatModel: String by rootProject.extra

testing {
    suites {

        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project(":lib"))
                implementation(libs.kotlin.test)
            }
        }

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

                    dependsOn(tasks.installDist)
                    systemProperty(
                        "momo.serverBin",
                        layout.buildDirectory.file("install/server/bin/server").get().asFile.absolutePath,
                    )
                    systemProperty("aiRouter.baseUrl", aiRouterBaseUrl)
                    systemProperty("aiRouter.chatModel", aiRouterChatModel)

                    outputs.cacheIf { false }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("liveTest"))
}
