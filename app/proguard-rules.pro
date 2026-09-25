# TDLib: the native library calls back into these classes by name via JNI.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# kotlinx.serialization models for TMDB
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.tgfinder.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose/debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
