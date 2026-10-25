-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Mappls map SDK (native code and reflection call back into these classes)
-keep class com.mappls.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn com.mappls.**
-dontwarn com.mapbox.**
