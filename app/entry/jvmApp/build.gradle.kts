import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.configure {
            dependencies {
                implementation(project(":app:ui"))

                implementation(compose.desktop.currentOs)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)

                implementation(libs.koin.core)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mochame.app.entry.jvm.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            optimize = true
        }

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MochaMe"
            packageVersion = "0.1.0"
            description = "MochaMe Local-First"
            vendor = "MochaMe"

            linux {
                shortcut = true
                menuGroup = "Utility"
                iconFile.set(project.file("src/jvmMain/resources/icons/icon.png"))
                debMaintainer = "omdavison@proton.me"
            }

            windows {
                shortcut = true
                menuGroup = "MochaMe"
                iconFile.set(project.file("src/jvmMain/resources/icons/icon.ico"))
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f8e7d21-9b34-4c56-8a12-123456789abc"
            }
        }
    }

    tasks.withType<JavaExec> {
        systemProperty("dark.theme", System.getProperty("dark.theme") ?: "true")
    }
}
