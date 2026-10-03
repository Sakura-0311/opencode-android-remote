@echo off
echo === 开始编译 OpenCode Android APK ===

where java >nul 2>nul
if %errorlevel% neq 0 (
    echo 错误: 未检测到 Java 环境，请先安装 JDK 17 或更高版本。
    pause
    exit /b 1
)

if exist gradlew.bat (
    call gradlew.bat assembleDebug
) else (
    call gradle assembleDebug
)

echo.
echo === 编译完成 ===
echo APK 文件位于: app\build\outputs\apk\debug\app-debug.apk
pause
