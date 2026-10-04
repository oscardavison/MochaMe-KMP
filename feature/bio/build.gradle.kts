plugins {
    id("mocha.convention.feature")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLint)
}


kotlin {
    android { namespace = "com.mochame.bio" }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":core:utils"))
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.io.core)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(project(":core:test:fixtures-utils"))
            implementation(project(":core:test:fixtures-platform"))
            implementation(project(":core:test:fixtures-node"))
            implementation(project(":core:test:fixtures-sync-api"))
        }
    }
}