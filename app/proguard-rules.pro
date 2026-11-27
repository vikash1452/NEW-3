# Battery Boss R8 Obfuscation & Keep Rules

# Repackage obfuscated classes into a single flat package and hide source file names
-repackageclasses 'o'
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations

# Keep @Keep annotated classes/members (Models, Entities, etc.)
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}

# Keep Room Databases & Entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep AndroidManifest Services, Receivers, Activities
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService

# Keep Media3 / ExoPlayer & Coil GIF runtime reflection targets
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class coil.decode.** { *; }

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
