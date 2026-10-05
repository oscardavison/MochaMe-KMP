plugins {
    id("mocha.convention.feature")
}

kotlin {
    android { namespace = "com.mochame.node" }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":core:test:fixtures-utils"))
        }
    }
}