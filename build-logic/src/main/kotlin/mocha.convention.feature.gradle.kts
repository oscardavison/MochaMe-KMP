import com.mochame.gradle.configureTargets
import com.mochame.gradle.getLibrary
import com.mochame.gradle.libs
import com.mochame.gradle.standardConfigurations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.insert-koin.compiler.plugin")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlinx.kover")

}

standardConfigurations()

kotlin {
    configureTargets(
        project = this@Project,
        libs = libs,
        includeTestBuilders = true
    )

    sourceSets {
        val commonMainProvider = named("commonMain")
        commonMainProvider.configure {
            dependencies {
                implementation(project(":core:annotations"))
                implementation(project(":core:utils"))
                implementation(project(":core:logger"))
                implementation(project(":sync:api"))

                implementation(libs.getLibrary("room-runtime"))
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val roomCompiler = libs.getLibrary("room-compiler")

    kotlin.targets.configureEach {
        if (this !is KotlinMetadataTarget) {
            val targetName = name.replaceFirstChar { it.uppercase() }

            val kspNames = if (name == "android") {
                listOf("kspAndroidHostTest", "kspAndroidDeviceTest")
            } else {
                listOf("ksp${targetName}Test")
            }

            kspNames.forEach { kspName ->
                configurations.findByName(kspName)?.let { config ->
                    project.dependencies.addProvider(config.name, roomCompiler)
                }
            }
        }
    }
}

// To resolve:
//     Reason: Task ':mocha:feature:bio:generateAndroidHostTestLintModel' uses this output of task
//     ':mocha:feature:bio:kspAndroidHostTest' without declaring an explicit or implicit dependency.
//     This can lead to incorrect results being produced, depending on what order the tasks are executed.
//
// Gradle 9+ implicit dependency fix: KSP output directories are consumed by AGP's
// lint tasks without a declared task graph edge. This causes non-deterministic build
// results when Gradle parallelises the configuration phase.
//
// Root cause: AGP's LintModelWriterTask and AndroidLintAnalysisTask read from the
// KSP-generated source directories but declare no dependency on the KSP tasks that
// produce them. Neither AGP nor KSP expose a stable public API to wire this edge
// directly.
plugins.withId("com.google.devtools.ksp") {
    val allKspTasks = tasks.matching {
        it.name.startsWith("ksp") && !it.name.contains("Metadata")
    }

    tasks.configureEach {
        val isLintTask = name.startsWith("lintAnalyze") ||
                name.startsWith("generate") && name.contains("LintModel")

        if (isLintTask) {
            dependsOn(allKspTasks)
        }
    }
}