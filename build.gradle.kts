import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Umbrella build: the library lives in lib/, the HTTP server in server/.
// This script only carries the conventions shared by every module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

// ─── Live-suite configuration ─────────────────────────────────────────
//
// Resolved once here so both modules' live suites share one set of
// defaults. Each setting comes from a Gradle property, then an environment
// variable, then the default; blank values (e.g. -PaiRouterBaseUrl= or an
// exported-but-empty variable) count as unset. Resolving via `orNull` on
// value-source providers keeps this configuration-cache safe.
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

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            // Require an explicit visibility modifier and explicit return type on every
            // public/protected declaration — keeps the published API surface intentional.
            explicitApi = ExplicitApiMode.Strict
            compilerOptions {
                allWarningsAsErrors = true
            }
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        dependencies {
            // Lint formatting rules (folds ktlint into detekt — no separate ktlint plugin)
            "detektPlugins"(libs.detekt.formatting)
        }
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("detekt.yml"))
            // Scan every source set a module may have; missing folders are ignored.
            source.setFrom(
                files(
                    "src/main/kotlin",
                    "src/test/kotlin",
                    "src/testFixtures/kotlin",
                    "src/liveTest/kotlin",
                    "src/containerTest/kotlin",
                ),
            )
        }
        tasks.withType<Detekt>().configureEach {
            // Fail the build on any detekt finding.
            ignoreFailures = false
        }
    }
}
