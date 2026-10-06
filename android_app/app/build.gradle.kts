plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // v2.6: SBOM 生成（cyclonedx-bom.json，随 release 产物发布）
    id("org.cyclonedx.bom") version "2.3.1"
}

import java.util.Properties

// v2.6: SBOM 输出固定为 build/reports/cyclonedx-bom.json（插件默认输出到 build/reports/）
tasks.cyclonedxBom {
    setOutputFormat("json")
    setOutputName("cyclonedx-bom")
}


// N-1: 签名密钥永不进仓库。按优先级读取：
//   1) 环境变量 RELEASE_KEYSTORE_FILE / RELEASE_KEYSTORE_PASSWORD /
//      RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD（CI 从 Secrets 注入）
//   2) android_app/keystore.properties（本地开发，已 gitignore）
//   v2.2.1-D: 缺失时 assembleRelease 直接失败（fail-fast），不再静默降级为 debug 签名；
//   本地调试请用 assembleDebug。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(key))?.takeIf { it.isNotBlank() }
val releaseStorePath = secret("storeFile", "RELEASE_KEYSTORE_FILE")
val releaseStorePass = secret("storePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPass = secret("keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePass, releaseKeyAlias, releaseKeyPass)
    .all { !it.isNullOrBlank() }
// v2.2.1-D: release 构建 fail-fast——缺密钥时直接失败，不再静默用 debug 签名。
// 用 taskGraph.whenReady（assemble* task 由 AGP 在配置期后创建，named() 会找不到）。
val releaseSigningFailMsg =
    "v2.2.1-D: 缺少 release 签名密钥，禁止构建 release 包。" +
    "请配置环境变量 RELEASE_KEYSTORE_FILE/RELEASE_KEYSTORE_PASSWORD/" +
    "RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD，或 android_app/keystore.properties；" +
    "本地调试请用 assembleDebug。"
gradle.taskGraph.whenReady {
    if (!hasReleaseSigning && allTasks.any { it.name == "assembleRelease" }) {
        throw GradleException(releaseSigningFailMsg)
    }
}

android {
    namespace = "com.opencode.android"
    compileSdk = 36

    // B-12: versionCode 随 versionName 自动递增（2.0.0 -> 20000；4.0.0 -> 40000）
    val appVersionName = "5.0.2"
    val appVersionCode = appVersionName.split(".").let { p ->
        p[0].toInt() * 10000 + p.getOrElse(1) { "0" }.toInt() * 100 + p.getOrElse(2) { "0" }.toInt()
    }

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        debug {
            // v2.4: debug 与 release 分离，避免调试包覆盖正式包
            applicationIdSuffix = ".debug"
        }
        release {
            // v4.2.0/O1: shrinkResources 开启（全仓无 getIdentifier 动态资源引用，
            // emulator-smoke（tag 构建）兜底 R8/资源裁剪导致的启动问题；fullMode 不开）。
            // 回滚只需改回 false。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // v2.2.1-D: 缺密钥时 assembleRelease 在 doFirst 即失败，不再降级为 debug 签名
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        // v5.0.2: 从 Java 8 提到 17（AGP 8.x + JDK 17 的常规目标；1.8 早已过时）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // v5.0.2: Kotlin 2.4 起 kotlinOptions.jvmTarget 字符串 DSL 已移除，
    // 改用顶层 kotlin { compilerOptions }（见文件末尾同名的 kotlin 块）
    buildFeatures {
        compose = true
        // B-12: UpdateChecker 需要读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // v5.0.2: 依赖升级。版本不是「取最新」，而是取**当前工具链能吃下的最新**
    // ——我逐个读了各版本 AAR 里的 aar-metadata.properties（minCompileSdk / minAgp）：
    //   core 1.19.x      需要 compileSdk 37 + AGP 9.1   → 用 1.18.0（要求 36 / 8.9.1）
    //   lifecycle 2.11.x 需要 compileSdk 37 + AGP 9.1   → 用 2.10.0（要求 35 / 8.6.0）
    //   compose 1.12.x   需要 compileSdk 37 + AGP 9.1   → BOM 2026.05.01 = 1.11.2
    //   okhttp 5.5.x     需要 compileSdk 37             → 用 5.4.0（要求 36）
    //   acra 5.14.x      需要 compileSdk 37             → 用 5.13.1
    // 想要再往前升，前提是把 AGP 提到 9.1+ / Gradle 9 / compileSdk 37，属于整链迁移。
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Jetpack Compose（BOM 统一管理 compose 各库版本）
    // v5.0.2: 2024.10.01 → 2026.05.01（Compose 1.11.2）。
    // 为什么不取最新的 2026.09.00（Compose 1.12.1）：我读了各版本 AAR 的
    // aar-metadata.properties —— 1.12.x 要求 minCompileSdk=37 且 AGP ≥ 9.1.0，
    // 落到 AGP 9.x + Gradle 9 的整链迁移；1.11.x 只要求 minCompileSdk=35、AGP ≥ 8.6，
    // 是当前工具链（AGP 8.13.2 + compileSdk 36）能吃下的最新版本。
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp WebSocket Client（4.12 → 5.4：5.x 已稳定；5.5 起要求 compileSdk 37）
    // v5.0.2: 删除 androidx.security:security-crypto（EncryptedSharedPreferences）。
    // 官方已弃用、1.1.0-alpha06 多年未更新；项目尚无线上用户，无存量数据需迁移，
    // 安全存储统一走下面的 Tink（主密钥由 Android Keystore 保护）。
    implementation("com.google.crypto.tink:tink-android:1.23.0")  // 已是最大稳定版
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // v1.6 P0 扫码配对：CameraX + ML Kit 条码扫描
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    // 对外分发：ACRA 崩溃上报（HTTP Sender，自建 Relay 接收端）
    // 注：排除 auto-service 传递的 Guava，避免与 CameraX 的 ListenableFuture 冲突
    implementation("ch.acra:acra-http:5.13.1") {
        exclude(group = "com.google.guava", module = "guava")
    }

    // Debugging UI Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // v2.3: 本地单测（Backoff / AppLog 脱敏等纯 Kotlin 逻辑）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // v4.3.1: E2EE 模拟器联调 instrumentation 测试
    // 注：E2EE 集成测试走 tests/e2ee/ 纯 Python 协议级联调（见 .github/workflows/emulator-e2ee.yml），不依赖 androidTest
    // 注：不用 kotlinx-coroutines-test（会引入未锁定的 kotlin-reflect 2.4.10）；runBlocking 走主依赖的 coroutines-core
}

// v5.0.2: Kotlin 2.4 起 kotlinOptions.jvmTarget 字符串 DSL 已移除，改用 compilerOptions。
// 与上面 android.compileOptions 的 Java 17 保持一致。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
