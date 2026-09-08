plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    `java-library`
}

dependencies {

    api(libs.ai.router.sdk)

    implementation(libs.kaml)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
