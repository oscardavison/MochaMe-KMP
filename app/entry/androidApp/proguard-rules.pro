# Usage shifts from 17MB to 7MB

-keep class org.koin.core.annotation.** { *; }

-keep @org.koin.core.annotation.* class * { *; }-keep class * extends org.koin.core.module.Module { *; }
-keepattributes *Annotation*,InnerClasses,EnclosingMethod