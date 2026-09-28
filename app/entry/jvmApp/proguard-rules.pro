## Broken for now
#
## Metadata
#-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
#
## App code
#-keep class com.mochame.** { *; }
#
## Kotlin Coroutines
#-keep class kotlinx.coroutines.** { *; }
#-dontwarn kotlinx.coroutines.**
#
## Compose Desktop UI runtime
#-keep class androidx.compose.** { *; }
#-keep class org.jetbrains.skiko.** { *; }
#-dontwarn org.jetbrains.skiko.**
#-keep class org.jetbrains.skia.** { *; }
#-dontwarn org.jetbrains.skia.**
#
## Room and SQLite
#-keep class androidx.room.** { *; }
#-keep class * extends androidx.room.RoomDatabase { *; }
#-keep class *_Impl { *; }
#-keep class org.sqlite.** { *; }
#-keep class java.sql.** { *; }
#
## DI, logging, networking, and multiplatform I/O
#-keep class org.koin.** { *; }
#-keep class co.touchlab.kermit.** { *; }
#-keep class io.ktor.** { *; }
#-keep class kotlinx.io.** { *; }
#-keep class kotlinx.datetime.** { *; }
