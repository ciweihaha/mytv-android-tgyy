# mytv-android ProGuard / R8 rules (round 8).
# Release builds run with minifyEnabled + shrinkResources. The default
# proguard-android-optimize.txt and the libraries' own consumer rules cover
# most cases; the rules below cover the gaps R8 cannot infer statically.

# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx-serialization ----
# @Serializable classes are (de)serialized via generated $$serializer classes
# looked up by reflection; keep them and their members.
# (Per kotlinx-serialization's own ProGuard guidance.)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keep class **$$serializer { *; }
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}

# ---- Media3 FFmpeg extension (app/libs/lib-decoder-ffmpeg-release.aar) ----
# DefaultRenderersFactory loads the FFmpeg audio renderer by class name
# via reflection (EXTENSION_RENDERER_MODE_ON); keep it explicitly so R8
# cannot strip the local AAR's classes. Note the package is
# androidx.media3.decoder.ffmpeg (where the AAR's classes actually live,
# incl. FfmpegAudioRenderer) — NOT androidx.media3.exoplayer.ffmpeg,
# which does not exist; the old rule kept nothing.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# ---- Notes: libraries that ship their own consumer rules ----
# OkHttp / Okio, Compose (incl. tv-material), Media3 core, lifecycle and
# activity all bundle consumer ProGuard rules; no extra rules needed here.
# androidasync and qrose are only ever called directly (no reflection), so
# R8 keeps their reachable code automatically.
