# R8 rules for release builds.
#
# The two hazards here are reflection-driven: Gson reads field names off classes, and
# the transition preference is stored as an enum constant name. R8 renames both by
# default, and neither failure is visible at build time — you get a frame that has
# forgotten its settings and an empty photo index, at runtime, on the wall.

# --- Gson ---------------------------------------------------------------------
# PhotoStore.Entry is serialised to index.json by field name. Renaming those fields
# silently invalidates every previously written index, so the frame would rebuild its
# whole library from scratch after an update — or fail to read it at all.
-keep class com.example.portalgallery.data.store.PhotoStore$Entry { *; }
-keepclassmembers class com.example.portalgallery.data.store.PhotoStore$Entry {
    <fields>;
}

# Gson uses generic type information from signatures for TypeToken.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# --- Enum names stored in preferences -----------------------------------------
# Transition is persisted by `name` and read back with a string comparison, so the
# constant names must survive obfuscation.
-keepclassmembers enum com.example.portalgallery.ui.slideshow.Transition {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# General enum contract, in case another enum is persisted later.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Components referenced by name --------------------------------------------
# Activities and receivers are kept via the manifest, but SlideshowActivity is also
# launched by string from WakeAlarm and BootReceiver. Keeping it explicitly makes that
# dependency obvious rather than incidental.
-keep class com.example.portalgallery.ui.slideshow.SlideshowActivity { *; }
-keep class com.example.portalgallery.data.schedule.** { *; }

# --- Presence detection (CameraX + ML Kit) ------------------------------------
# ML Kit loads its bundled model through reflection and native bridges; stripping or
# renaming those leaves face detection silently failing in release builds only, which
# would look like "presence never detects anyone" rather than like a build problem.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# The detector is instantiated directly, but keeping it makes the ML Kit and CameraX
# dependency explicit alongside the rules above.
-keep class com.example.portalgallery.data.presence.** { *; }

# --- Glide --------------------------------------------------------------------
# Glide ships consumer rules, so this is only the belt-and-braces part.
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# --- OkHttp -------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep line numbers so a release crash log is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
