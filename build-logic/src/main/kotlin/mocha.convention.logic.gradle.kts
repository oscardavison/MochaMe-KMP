import com.mochame.gradle.configureTargets
import com.mochame.gradle.libs
import com.mochame.gradle.standardConfigurations

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.insert-koin.compiler.plugin")
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
            }
        }
    }
}