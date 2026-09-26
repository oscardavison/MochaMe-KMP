import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.koin.compiler)
    kotlin("jvm")
    id("application")
}

dependencies {
    implementation(project(":core:sync-api"))
    implementation(project(":core:logger"))
    implementation(project(":core:utils"))
    implementation(libs.hikaricp)
    implementation(libs.bundles.ktor.server)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:test:fixtures-utils"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.mochame.server.ServerMainKt")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1 // Intra-Task - Prevents Worker 1 (:server:test) from spawning multiple JVMs. It runs all server test specs sequentially inside a single JVM process.
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        outputs.upToDateWhen { false }
        showStandardStreams = true
        showExceptions = false
        events(TestLogEvent.FAILED)
    }
}