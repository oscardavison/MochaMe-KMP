plugins {
    id("mocha.convention.ui")
}

kotlin {
    android { namespace = "com.mochame.bio.ui" }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:utils"))
            implementation(project(":feature:bio"))
            implementation(project(":sync:api"))

            implementation(libs.kotlinx.datetime)
        }
    }
}
