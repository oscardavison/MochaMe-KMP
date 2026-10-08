plugins {
    id("mocha.convention.provider")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.sync.fixtures" }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":sync:api"))
            implementation(libs.kotlinx.atomicfu)
        }
    }
}