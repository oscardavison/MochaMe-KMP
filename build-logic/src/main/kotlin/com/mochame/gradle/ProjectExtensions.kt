package com.mochame.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.koin.compiler.plugin.KoinGradleExtension

// ** Extension Functions **
/**
 * Standard utilities updated for the Koin Compiler Plugin (Post-KSP).
 * Handles configuration for the Koin Gradle extension, and
 * test logging for tasks.
 */
fun Project.standardConfigurations() {
    pluginManager.withPlugin("io.insert-koin.compiler.plugin") {
        mochaKoin {
            userLogs.set(true)
            debugLogs.set(false)
            unsafeDslChecks.set(true)
            skipDefaultValues.set(true)
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            outputs.upToDateWhen { false }
            showStandardStreams = true
            showExceptions = false
            events(TestLogEvent.FAILED)
        }
        //Individual Test JVMs (GradleWorkerMain): Caps each test runner at 768 MB instead of letting them default
        maxHeapSize = "512m"
    }
}

/**
 * Accessor for the Koin Compiler.
 */
private fun Project.mochaKoin(configure: KoinGradleExtension.() -> Unit) {
    extensions.configure<KoinGradleExtension>(configure)
}


// ** Extension Properties **
/**
 * Accessor for the Version Catalog.
 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>()
        .find("libs")
        .orElseThrow {
            IllegalStateException("[MochaMe] 'libs' Version Catalog retrieval failed. Confirm settings.gradle.kts.")
        }
/**
 * Accessor for OS detection.
 * Tracked by Gradle's Configuration Cache via 'project.providers'.
 */
val Project.isMac: Boolean
    get() = providers.systemProperty("os.name")
        .getOrElse("Unknown")
        .contains("Mac", ignoreCase = true)