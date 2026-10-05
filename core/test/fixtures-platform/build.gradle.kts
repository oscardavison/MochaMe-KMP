
plugins {
    id("mocha.convention.provider")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.platform.fixtures" }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:platform"))
            implementation(project(":sync:api"))

            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
    }
}