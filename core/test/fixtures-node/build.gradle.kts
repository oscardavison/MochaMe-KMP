plugins {
    id("mocha.convention.provider")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.node.fixtures" }

    sourceSets {
        commonMain.dependencies {
            api(project(":sync:node"))
            implementation(libs.kotlinx.atomicfu)
            implementation(project(":sync:api"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}