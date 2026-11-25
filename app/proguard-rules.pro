# ---- 通用安全规则 ----
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-keepattributes Exceptions

# Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---- Shizuku（AIDL/Binder，需保留） ----
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---- 安全/加密相关库的可选依赖，缺失时不报错 ----
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- miuix / kyant 液态玻璃：运行时反射与 shader 类，禁止裁剪 ----
-keep class top.yukonga.miuix.** { *; }
-keep class com.kyant.** { *; }
-dontwarn top.yukonga.miuix.**
-dontwarn com.kyant.**

# ---- 本工程玻璃组件（Backdrop/动画/绘制用反射字段） ----
-keep class com.luminaauth.ui.glass.** { *; }
-keep class com.luminaauth.ui.components.** { *; }

# ---- 动态配色（materialkolor 使用运行时生成） ----
-keep class com.materialkolor.** { *; }

# ---- 崩溃捕获需要完整堆栈 ----
-keep class com.luminaauth.CrashLog { *; }
-keep class com.luminaauth.LuminaApp { *; }
