plugins {
    id("mocha.convention.logic")
    alias(libs.plugins.androidLint)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.atomicfu.compiler)
}

kotlin {

    linuxX64 {
        compilations.getByName("main") {
            val openssl = cinterops.create("openssl") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/openssl.def"))
            }
        }
    }

    android { namespace = "com.mochame.platform" }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:utils"))
            implementation(project(":core:logger"))
            implementation(project(":sync:api"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.io.core)

            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.atomicfu)
        }

        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.work.runtime.ktx)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
        }

    }
}
