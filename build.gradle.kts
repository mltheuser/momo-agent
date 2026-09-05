import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

fun resolveLiveSetting(propertyName: String, envName: String, default: String): String =
    providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
        ?: default

val aiRouterBaseUrl: String by extra(
    resolveLiveSetting("aiRouterBaseUrl", "AI_ROUTER_BASE_URL", "http://localhost:8787"),
)

val aiRouterChatModel: String by extra(
    resolveLiveSetting("aiRouterChatModel", "AI_ROUTER_CHAT_MODEL", "claude-sonnet-5:cloud@anthropic"),
)

subprojects {
    group = "codes.momo"
    version = "0.1.0"

    tasks.withType<Test>().configureEach {
        val minutes = if (name == "liveTest") 15 else 2
        systemProperty("junit.jupiter.execution.timeout.default", "${minutes}m")
        // SEPARATE_THREAD makes the timeout preemptive; on the test thread a hang is never reported.
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

        outputs.upToDateWhen { false }
    }

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)

            explicitApi = ExplicitApiMode.Strict
            compilerOptions {
                allWarningsAsErrors = true
            }
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        dependencies {

            "detektPlugins"(libs.detekt.formatting)
        }
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("detekt.yml"))

            source.setFrom(
                files(
                    "src/main/kotlin",
                    "src/test/kotlin",
                    "src/liveTest/kotlin",
                ),
            )
        }
        tasks.withType<Detekt>().configureEach {

            ignoreFailures = false
        }
    }
}
