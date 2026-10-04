plugins {
    id("mocha.convention.feature")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.sync" }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqlite.bundled)
            implementation(libs.bundles.ktor.common)
        }

        commonTest.dependencies {
            implementation(project(":core:test:fixtures-node"))
            implementation(project(":core:test:fixtures-utils"))
            implementation(project(":core:test:fixtures-platform"))
        }
    }
}
