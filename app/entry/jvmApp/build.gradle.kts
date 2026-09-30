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
                implementation(libs.slf4j.nop)
                implementation(libs.koin.core)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mochame.app.entry.jvm.MainKt"

//        buildTypes.release.proguard { // Currently not working
//            configurationFiles.from(project.file("proguard-rules.pro"))
//            optimize = true
//            obfuscate = false
//        }

        // JVM Runtime Flags (Memory & Wayland/Display tuning) - Need to verify this more
        jvmArgs += listOf(
            "-Xms64m",                   // Low initial heap
            "-Xmx512m",                  // Cap maximum heap to keep desktop RAM usage modest
            "-XX:+UseG1GC",              // Low-latency garbage collection
            "-Dsun.java2d.uiScale.enabled=true" // Ensure clean text scaling on 4K/HiDPI
        )

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MochaMe"
            packageVersion = "0.1.0"
            description = "MochaMe Local-First"
            vendor = "MochaMe"
            appResourcesRootDir.set(project.file("src/jvmMain/resources/package-resources"))

            linux {
                shortcut = true
                menuGroup = "Utility"
                appCategory = "Utility"
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

    tasks.withType<JavaExec>().configureEach {
        systemProperty(
            "dark.theme",
            providers.systemProperty("dark.theme").orElse("true")
        )
    }
}