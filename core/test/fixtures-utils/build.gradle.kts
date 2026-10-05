plugins {
    id("mocha.convention.provider")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.utils.fixtures" }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:utils"))
            api(project(":sync:api"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
        }
    }
}