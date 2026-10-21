# Proguard rules for imgtool-android16

# Keep Shizuku private newProcess method accessed via reflection in ShizukuManager
-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
# NOTE: Broad -keep class/interface rikka.shizuku.** rules removed.
# The Shizuku API AAR (dev.rikka.shizuku:api) bundles its own consumer ProGuard rules.

# NOTE: -keepclassmembers class com.onlasdan.studio.core.** { *; } removed.
# ImgEntry, ImgArchive, ArchiveRepository are accessed via static typed references only
# (no reflection). R8 will retain all reachable members automatically.

# NOTE: -keepattributes *Annotation* removed.
# Already included in the default proguard-android-optimize.txt.

-dontwarn java.lang.Process
