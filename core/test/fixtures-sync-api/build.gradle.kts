plugins {
    id("mocha.convention.provider")
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {
    android { namespace = "com.mochame.sync.fixtures" }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:sync-api"))
            implementation(project(":core:test:fixtures-utils"))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
