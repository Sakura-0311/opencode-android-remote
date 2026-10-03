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
    ./gradlew assembleDebug
else
    gradle assembleDebug
fi

echo ""
echo "=== 编译完成 ==="
echo "APK 输出路径通常位于: app/build/outputs/apk/debug/app-debug.apk"
