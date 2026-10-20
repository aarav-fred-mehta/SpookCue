# Android default optimizations
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime JNI bindings
-keep class ai.onnxruntime.** { *; }

# Kotlinx Serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# SpookCue models
-keep class dev.spookcue.data.model.** { *; }
-keep class dev.spookcue.data.local.** { *; }
