# kotlinx.serialization keeps generated serializers on the companion object.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.longislandicetea.dshnative.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.longislandicetea.dshnative.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp ships optional platform integrations that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
