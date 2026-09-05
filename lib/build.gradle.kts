plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    `java-library`
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
}

// The library has no test suite of its own: it is tested end to end through
// the server's live suite, the only tier the tree keeps (docs/testing.md).
