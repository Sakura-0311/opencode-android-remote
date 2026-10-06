#!/usr/bin/env bash
# OpenCode Android APK 构建脚本

set -e

echo "=== 开始编译 OpenCode Android APK ==="

if ! command -v java &> /dev/null; then
    echo "错误: 未检测到 Java 环境，请先安装 JDK 17 或更高版本。"
    exit 1
fi

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "提示: 未检测到 ANDROID_HOME 环境变量。如果编译报错，请确保配置了 Android SDK 路径。"
fi

if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    # v5.0.1: 仓库终于带上了 Gradle Wrapper（gradle/wrapper/gradle-wrapper.properties
    # 固定 8.7，与 CI 的 gradle-version 一致），本地构建不再依赖机器上装的 gradle 版本。
    ./gradlew assembleDebug
elif command -v gradle &> /dev/null; then
    echo "提示: 未找到 ./gradlew（Wrapper 缺失），退回到系统 gradle：$(gradle --version | grep Gradle)"
    gradle assembleDebug
else
    echo "错误: 既没有 ./gradlew 也没有系统 gradle，无法构建。"
    exit 1
fi

echo ""
echo "=== 编译完成 ==="
echo "APK 输出路径通常位于: app/build/outputs/apk/debug/app-debug.apk"
