plugins {
    id("mocha.convention.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.material3)
            implementation(libs.compose.foundation)

        }
    }
    android { namespace = "com.mochame.core.design" }
}

compose.resources {
    packageOfResClass = "com.mochame.core.design.generated.resources"
}