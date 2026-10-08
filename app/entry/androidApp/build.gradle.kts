plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.koin.compiler)
}

val appVersion = "0.2.0"

base {
    archivesName.set("mochame-v${appVersion}")
}

android {
    namespace = "com.mochame.app.entry.android"

    compileSdk = libs.versions.android.sdk.compile.get().toInt()

    defaultConfig {
        applicationId = "com.mochame.androidapp"
        minSdk = libs.versions.android.sdk.min.get().toInt()
        targetSdk = libs.versions.android.sdk.target.get().toInt()
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keyPath = providers.gradleProperty("MOCHAME_KEYSTORE_PATH")
                .orElse(providers.environmentVariable("KEYSTORE_PATH"))
                .orNull

            if (keyPath != null && file(keyPath).exists()) {
                storeFile = file(keyPath)
                storePassword = providers.gradleProperty("MOCHAME_KEYSTORE_PASSWORD")
                    .orElse(providers.environmentVariable("KEYSTORE_PASSWORD"))
                    .orNull
                keyAlias = providers.gradleProperty("MOCHAME_KEY_ALIAS")
                    .orElse(providers.environmentVariable("KEY_ALIAS"))
                    .getOrElse("mochame")
                keyPassword = providers.gradleProperty("MOCHAME_KEY_PASSWORD")
                    .orElse(providers.environmentVariable("KEY_PASSWORD"))
                    .orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }


}

dependencies {
    implementation(project(":core:annotations"))
    implementation(project(":app:ui"))
    implementation(project(":sync:api"))
    implementation(libs.slf4j.nop)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.koin.android)
    implementation(libs.koin.annotations)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

}