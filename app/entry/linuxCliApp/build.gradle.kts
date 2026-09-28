plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.koin.compiler)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.mochame.app.entry.linux.main"
                baseName = "mochame-cli"

                linkerOpts("-lcrypto", "-lpthread", "-ldl")
                linkerOpts("-Wl,--allow-shlib-undefined")
            }
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":app:assembly"))
            implementation(project(":feature:bio"))

            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.register<Copy>("installLocal") {
    description = "Local Install"
    dependsOn("linkReleaseExecutableLinuxX64")
    val binary = layout.buildDirectory.file("bin/linuxX64/releaseExecutable/mochame-cli.kexe")
    from(binary)
    into(File(System.getProperty("user.home"), ".local/bin"))
    rename { "mochame-cli" }
    filePermissions {
        user {
            read = true
            write = true
            execute = true
        }
    }
}