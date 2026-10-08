plugins {
    `kotlin-dsl`
}

dependencies {
    api(libs.koin.compiler.gradle.plugin)
    api(libs.ksp.gradle.plugin)
    api(libs.kotlin.gradle.plugin)
    api(libs.room.gradle.plugin)
    api(libs.androidKmpLibrary)
    api(libs.compose.gradlePlugin)
    api(libs.kotlin.composeCompilerPlugin)
    api(libs.kover.gradle.plugin)
}

/**
 *   Step-by-Step: The Two Separate Builds
 *
 *   Gradle actually runs two completely separate builds when you run a command:
 *
 *     [ Phase 1: The Build-Logic Build ] (An included build)
 *       Inputs:  build-logic/src/main/kotlin/*.gradle.kts
 *       Needs:   Kover classes on its classpath to COMPILE those Kotlin scripts
 *       Config:  build-logic/build.gradle.kts -> dependencies { api(libs.kover.gradle.plugin) }
 *       Output:  A JAR containing mocha.convention.logic, mocha.convention.feature, etc.
 *
 *                      │  (Classpath handed over to Phase 2)
 *                      ▼
 *
 *     [ Phase 2: The Main Project Build ] (The actual app)
 *       Inputs:  settings.gradle.kts, root build.gradle.kts, :sync:engine, :feature:bio
 *       Needs:   To EXECUTE the Kover plugin logic
 *       Config:  Root build.gradle.kts -> plugins { alias(libs.plugins.kover) }
 *   ──────
 *   ### Why build-logic/build.gradle.kts uses dependencies { api(...) }
 *
 *   Look inside mocha.convention.logic.gradle.kts:
 *
 *     plugins {
 *         id("org.jetbrains.kotlin.multiplatform")
 *         id("org.jetbrains.kotlinx.kover") // <-- The Kotlin compiler has to compile this line!
 *     }
 *
 *   When Gradle compiles mocha.convention.logic.gradle.kts into Java bytecode (.class files), the Kotlin compiler must resolve what org.jetbrains.kotlinx.kover is.
 *   If you also write Kotlin extension code in build-logic like:
 *
 *     configure<kotlinx.kover.gradle.plugin.dsl.KoverReportExtension> { ... }
 *
 *   The Kotlin compiler needs the actual .jar file containing those classes to compile your code!
 *
 *   In Gradle, how do you give a project .jar files so its Kotlin code can compile?
 *   Via dependencies { api(...) }!
 *   That's why build-logic/build.gradle.kts declares:
 *
 *     dependencies {
 *         api(libs.kover.gradle.plugin)
 *         api(libs.kotlin.gradle.plugin)
 *         api(libs.room.gradle.plugin)
 *     }
 *
 *   You are telling Gradle: "To compile my convention scripts into .class files, fetch these Gradle plugin JARs and put them on build-logic's classpath."
 *   ──────
 *   ### Why the Root build.gradle.kts uses plugins { alias(...) }
 *
 *   In the root of your project (build.gradle.kts), you are not compiling new plugins. You are building your project.
 *
 *   When you write:
 *
 *     plugins {
 *         alias(libs.plugins.kover)
 *     }
 *
 *   You are telling Gradle:
 *
 *   1. "Take the Kover plugin that was published to Gradle."
 *   2. "Instantiate its Plugin.apply(project) method on THIS root project."
 *   3. "Register the koverHtmlReport, koverXmlReport, and root configuration extensions."
 *
 *   If you put api(...) in the root build.gradle.kts, Gradle would think you are trying to publish or export Kover as a library dependency of your root project.
 *   And conversely, if you only put plugins { alias(...) } in build-logic/build.gradle.kts, you would be applying Kover to build-logic itself (measuring test coverage of your build
 *   scripts!), rather than giving the convention scripts the classes they need to compile.
 *   ──────
 *   ### Summary Analogy
 *
 *    File				│ What it is                                   │ Why it uses what it uses
 *   ──────────────────────────────┼──────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────
 *    build-logic/build.gradle.kts │ The Factory (builds your convention plugins) │ Uses dependencies { api(...) } to import the raw construction materials (the Kover SDK/JAR) needed
 * 				│                                              │ to build your convention plugins.
 *    Root build.gradle.kts        │ The Foreman (runs the project)               │ Uses plugins { alias(...) } to actually turn on and run the Kover tool to aggregate all reports
 *                                 │                                              │ across the project.
 */