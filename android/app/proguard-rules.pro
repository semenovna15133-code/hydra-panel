# Keep kotlinx.serialization generated serializers
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
