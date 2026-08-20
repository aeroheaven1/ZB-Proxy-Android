# ZBProxy ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.zbproxy.android.proxy.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.** { *; }