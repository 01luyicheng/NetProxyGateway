# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Keep entry points used via reflection or Android framework
-keep class com.netproxy.gateway.NetProxyApp { *; }
-keep class com.netproxy.gateway.ui.MainActivity { *; }
-keep class com.netproxy.gateway.vpn.GatewayVpnService { *; }
-keep class com.netproxy.gateway.proxy.Socks5ProxyService { *; }

# Netty
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# MQTT
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Retrofit
-keepattributes Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
