plugins {
    id("mocha.convention.ui")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android { namespace = "com.mochame.app.ui" }

    sourceSets {
        commonMain.dependencies {
            api(project(":app:assembly"))
            implementation(project(":sync:api"))
            implementation(project(":core:utils"))
            implementation(project(":core:logger"))

            implementation(project(":feature:bio"))
            implementation(project(":feature:bio:ui"))

            implementation(libs.material.icons.extended)
            implementation(libs.kotlinx.datetime)
        }
    }
}

compose.resources {
    packageOfResClass = "com.mochame.app.ui.generated.resources"
}