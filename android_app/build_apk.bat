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
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo 错误: 既没有 gradlew.bat 也没有系统 gradle，无法构建。
        pause
        exit /b 1
    )
    echo 提示: 未找到 gradlew.bat（Wrapper 缺失），退回到系统 gradle。
    call gradle assembleDebug
)

echo.
echo === 编译完成 ===
echo APK 文件位于: app\build\outputs\apk\debug\app-debug.apk
pause
