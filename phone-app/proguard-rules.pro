# Keep serialization models
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep OkHttp & WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep ZXing
-keep class com.google.zxing.** { *; }

# Keep AudioFX
-keep class android.media.audiofx.** { *; }

# Keep Google Speech Micro JNI
-keep class com.google.speech.micro.** { *; }
-keepclassmembers class com.google.speech.micro.** { *; }
