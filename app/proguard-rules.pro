# kotlinx.serialization.
#
# The serializers are generated code reached through reflection-free but
# non-obvious paths, and R8 removed every one of them on the first local minified
# build: the release dex had no reference to `KSerializer` at all, while the debug
# dex had 518. Gradle would have merged the library's consumer rules; R8 invoked
# directly does not, so the keeps are stated here.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.**
# A generated serializer is a nested class named `$$serializer`, and every
# `@Serializable` companion exposes `serializer()`. Both are looked up by the
# runtime, so both are kept by name.
-keepclassmembers class io.github.longislandicetea.dshnative.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.longislandicetea.dshnative.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class io.github.longislandicetea.dshnative.**$$serializer { *; }
-keepclassmembers class io.github.longislandicetea.dshnative.** {
    public static ** INSTANCE;
    public static **[] $VALUES;
}
# The @Serializable annotation must survive on the classes, not just in general:
# the runtime reads it to look up the generated serializer.
-keep @kotlinx.serialization.Serializable class io.github.longislandicetea.dshnative.** { *; }
# OkHttp ships optional platform integrations that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The rest of this file is what the project itself needs. The rules below stand in
# for the consumer rules Gradle would merge automatically from the libraries'
# AARs; `tools/build-release.sh` invokes R8 directly and so has to be told.
#
# Compose reaches platform classes that android.jar does not publish (the
# RenderNode/DisplayListCanvas generation, gated behind an API check at runtime).
-dontwarn android.view.**
# androidx.concurrent's futures are optional: they are used only when a
# ListenableFuture integration is on the classpath.
-dontwarn com.google.common.util.concurrent.**

# kotlinx.coroutines' Main dispatcher.
#
# `kotlinx-coroutines-android` registers `AndroidDispatcherFactory` through
# `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`, so nothing
# references the class directly and R8 removed it. The minified app then died on
# `MainActivity.onCreate` with "Module with the Main dispatcher is missing" -- the
# release-only failure this whole local build exists to catch. Gradle merges these
# keeps from the library's consumer rules; invoking R8 directly does not.
-keepnames class kotlinx.coroutines.android.** { *; }
-keep class kotlinx.coroutines.android.** { *; }
# The service files themselves have to survive too, or the lookup finds nothing.
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
