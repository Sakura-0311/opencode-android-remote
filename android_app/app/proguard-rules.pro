# v3.3: R8 开启（minify=true；v4.2.0 起 shrinkResources=true，经 emulator-smoke 验证）。
#
# 以下依赖自带 consumer keep rules（已实测 AAR/JAR），无需手写：
# - ACRA 5.11.3（proguard.txt：插件反射、枚举、ErrorReporter）
# - ML Kit barcode-scanning 17.2.0（proguard.txt：proto 字段、native 方法）
# - tink-android 1.23.0（META-INF/proguard/protobuf.pro：shaded protobuf 反射）
# - OkHttp 4.12.0（META-INF/proguard/okhttp3.pro）
#
# 项目代码无反射、无 Gson 解析（org.json 手动解析），Compose 由 AGP 处理。
# v5.0.2: security-crypto 依赖与迁移路径已删除，对应的 keep/dontwarn 一并移除。

# Tink Android Keystore 集成（双保险）
-keep class com.google.crypto.tink.integration.android.** { *; }

# 数据模型：保留类名/字段名，崩溃上报与日志可读
-keepnames class com.opencode.android.data.model.** { *; }
-keepclassmembers class com.opencode.android.data.model.** { *; }

# v4.2.0/V4-2 纵深防御：release 剥除 android.util.Log.d/v 调用
# （日志开关误留也不会进 release 包；Log.e/w/i 保留）
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# v3.3: ACRA 的 auto-service 注解处理器（仅编译时）引用的 Guava 类，
# 运行时不需要；v2.2 已从依赖中排除 Guava（与 CameraX 冲突），此处消警告
-dontwarn com.google.auto.service.**
