rootProject.name = "MochaMe"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:entry:androidApp")
include(":app:entry:linuxCliApp")
include(":app:entry:jvmApp")
include(":app:assembly")
include(":app:ui")

include(":core:annotations")
include(":core:design")
include(":core:logger")
include(":core:utils")
include(":core:platform")

include(":core:test:support")
include(":core:test:test-logger")
include(":core:test:fixtures-utils")
include(":core:test:fixtures-platform")

include(":sync:protocol")
include(":sync:api")
include(":sync:server")
include(":sync:engine")

include("feature:bio")
include("feature:bio:ui")
include("feature:telemetry")
include("feature:resonance")