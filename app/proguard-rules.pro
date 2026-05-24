# Add project specific ProGuard rules here.

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.JsonDeserializer
-keep class * implements com.google.gson.JsonSerializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# Coil
-dontwarn coil.**

# Kotlin
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Keep data and API models from being obfuscated by R8 to prevent serialization errors
-keep class com.krishanagarwal.mynoo.data.** { *; }
