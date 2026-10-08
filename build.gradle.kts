plugins {
    // Android plugins
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.androidLint) apply false

    // Kotlin plugins
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false

    // Compose & Multiplatform tooling
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.atomicfu.compiler) apply false

    alias(libs.plugins.kover)
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        rootProject.dependencies {
            add("kover", this@subprojects)
        }
    }
}

kover {
    reports {
        total {
            filters {
                excludes {
                    packages(
                        "*.di",
                        "*.di.*"
                    )

                    packages(
                        "com.mochame.resonance.*",
                        "com.mochame.telemetry.*",
                        "com.mochame.bio.*"
                    )
                }
            }

            html {
                onCheck = true
            }
        }
    }
}