package com.mochame.gradle

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.api.artifacts.VersionCatalog

/**
 * Lifecycle-safe testing environment provider using lazy providers.
 */
fun KotlinMultiplatformExtension.configureTestTargets(libs: VersionCatalog) {
    sourceSets.apply {
        val commonTestProvider = named("commonTest")

        // Lazy Configuration
        commonTestProvider.configure {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.getLibrary("kotlinx-coroutines-test"))
                implementation(libs.getLibrary("turbine"))
                implementation(libs.getLibrary("koin-test"))
                implementation(libs.getLibrary("kermit-test"))

                implementation(project(":core:test:support"))
            }
        }

        named("jvmTest") {
            // Note: CommonTest link is handled implicitly by KGP for JVM
            dependencies {
                implementation(libs.getLibrary("test-junit-jupiter"))
            }
        }

        named("linuxX64Test") {
            dependsOn(commonTestProvider.get())
        }

        named("androidHostTest") {
            dependsOn(commonTestProvider.get())
            dependencies {
                implementation(libs.getLibrary("junit4"))
                implementation(libs.getLibrary("test-robolectric"))
                implementation(libs.getLibrary("androidx-test-core"))
                runtimeOnly(libs.getLibrary("junit-vintage-engine"))
            }
        }

        named("androidDeviceTest") {
            dependsOn(commonTestProvider.get())
            dependencies {
                implementation(libs.getLibrary("junit4"))
                implementation(libs.getLibrary("androidx-test-ext-junit"))
                implementation(libs.getLibrary("androidx-runner"))
                implementation(libs.getLibrary("androidx-test-core"))
                implementation(libs.getLibrary("koin-android"))
            }
        }
    }
}
