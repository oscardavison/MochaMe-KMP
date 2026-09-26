plugins {
    id("mocha.convention.assembler")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.app.assembly" }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)

            api(project(":core:platform"))
            implementation(project(":core:sync-api"))
            implementation(project(":node"))
            implementation(project(":sync-engine"))
            implementation(project(":core:logger"))
            implementation(project(":core:annotations"))
            implementation(project(":core:utils"))

            implementation(project(":feature:bio"))
            implementation(project(":feature:telemetry"))
            implementation(project(":feature:resonance"))
        }
    }
}

