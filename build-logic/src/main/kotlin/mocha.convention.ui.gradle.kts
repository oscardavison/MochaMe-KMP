import com.mochame.gradle.getLibrary
import com.mochame.gradle.getVersionAsInt
import com.mochame.gradle.getVersionAsString
import com.mochame.gradle.isMac
import com.mochame.gradle.libs
import com.mochame.gradle.standardConfigurations
import gradle.kotlin.dsl.accessors._7210b2c786089794992553e4167a8a51.sourceSets
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.insert-koin.compiler.plugin")
    id("org.jetbrains.compose")
    id("org.jetbrains.compose.hot-reload")
}

standardConfigurations()

kotlin {
    jvm()

    android {
        compileSdk = libs.getVersionAsInt("android-sdk-compile")
        minSdk = libs.getVersionAsInt("android-sdk-min")

        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.getVersionAsString("java-jvmTarget")))
        }
    }

    sourceSets {
        val commonMainProvider = named("commonMain")
        val isFixture = project.path.startsWith(":core:test:fixtures-")

        if (isFixture) {
            commonMainProvider.configure {
                dependencies {
                    implementation(project(":core:annotations"))
                    api(project(":core:test:support"))
                }
            }
        }
    }

    sourceSets {
        val commonMainProvider = named("commonMain")
        val isCore = project.path.startsWith(":core")

        commonMainProvider.configure {
            dependencies {
                if (!isCore) {
                    api(project(":core:design"))
                }
                implementation(project(":core:annotations"))

                api(libs.getLibrary("compose-components-resources"))
//                  implementation(libs.compose.material3.adaptive.layout)
//                  implementation(libs.compose.material3.adaptive.navigation)
                implementation(libs.getLibrary("compose-material3"))
                implementation(libs.getLibrary("compose-material3-adaptive-navigation-suite"))
                implementation(libs.getLibrary("navigation-compose"))

                implementation(libs.getLibrary("androidx-lifecycle-viewmodel"))
                implementation(libs.getLibrary("androidx-lifecycle-runtimeCompose"))
                implementation(libs.getLibrary("androidx-lifecycle-viewmodelCompose"))
                implementation(libs.getLibrary("androidx-lifecycle-viewmodelCompose"))

                implementation(libs.getLibrary("koin-compose-viewmodel"))
                implementation(libs.getLibrary("koin-compose"))
            }

            jvmMain.dependencies {
                implementation(libs.getLibrary("compose-uiTooling"))
            }
        }
    }

    if (project.isMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

compose.resources {
    publicResClass = true
    generateResClass = auto
}