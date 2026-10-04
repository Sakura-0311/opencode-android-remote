# v3.3: R8 开启（minify=true，shrinkResources=false 保守）。
#
# 以下依赖自带 consumer keep rules（已实测 AAR/JAR），无需手写：
# - ACRA 5.11.3（proguard.txt：插件反射、枚举、ErrorReporter）
# - ML Kit barcode-scanning 17.2.0（proguard.txt：proto 字段、native 方法）
# - tink-android 1.23.0（META-INF/proguard/protobuf.pro：shaded protobuf 反射）
# - OkHttp 4.12.0（META-INF/proguard/okhttp3.pro）
#
# 项目代码无反射、无 Gson 解析（org.json 手动解析），Compose 由 AGP 处理。

# security-crypto 1.1.0-alpha06（legacy 加密路径，无 consumer rules；保留至少 1 个版本）
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Tink Android Keystore 集成（双保险）
-keep class com.google.crypto.tink.integration.android.** { *; }

# 数据模型：保留类名/字段名，崩溃上报与日志可读
-keepnames class com.opencode.android.data.model.** { *; }
-keepclassmembers class com.opencode.android.data.model.** { *; }
