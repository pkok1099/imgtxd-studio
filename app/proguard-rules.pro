# Proguard rules for imgtool-android16

# Keep Shizuku reflection methods and IPC interfaces
-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Keep core IMG entries & versions
-keepclassmembers class com.onlasdan.studio.core.** { *; }

# Keep Compose and runtime annotations
-keepattributes *Annotation*
-dontwarn java.lang.Process
