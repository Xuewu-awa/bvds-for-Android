# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留 WebView JavaScript 接口方法
-keepclassmembers class com.xuewu.bvds.BridgeInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 WebView 相关
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-dontwarn android.webkit.**

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile