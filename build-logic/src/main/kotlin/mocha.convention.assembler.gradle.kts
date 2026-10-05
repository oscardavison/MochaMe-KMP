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
}

standardConfigurations()

kotlin {
    sourceSets {
        val commonMainProvider = named("commonMain")

        commonMainProvider.configure {
            dependencies {
                implementation(project(":core:annotations"))
                implementation(project(":core:utils"))
                implementation(project(":sync:api"))

                implementation(libs.getLibrary("room-runtime"))
            }
        }
    }

    configureTargets(
        project = this@Project,
        libs = libs
    )
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val roomCompiler = libs.getLibrary("room-compiler")

    kotlin.targets.configureEach {
        if (this !is KotlinMetadataTarget) {
            val kspConfigName = "ksp${name.replaceFirstChar { it.uppercase() }}"

            configurations.matching { it.name == kspConfigName }.configureEach {
                project.dependencies.addProvider(this.name, roomCompiler)
            }
        }
    }
}