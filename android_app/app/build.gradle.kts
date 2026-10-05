plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    compileSdk = 34

    // B-12: versionCode 随 versionName 自动递增（2.0.0 -> 20000；4.0.0 -> 40000）
    val appVersionName = "4.3.3"
    val appVersionCode = appVersionName.split(".").let { p ->
        p[0].toInt() * 10000 + p.getOrElse(1) { "0" }.toInt() * 100 + p.getOrElse(2) { "0" }.toInt()
    }

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 24
        targetSdk = 34
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // B-12: UpdateChecker 需要读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp WebSocket Client
        // Security Crypto for EncryptedSharedPreferences (SEC-05)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // v3.2: Tink 直连（官方推荐方向）。security-crypto 保留至少 1 个版本：旧实现仍需编译（迁移源+回退）
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // v1.6 P0 扫码配对：CameraX + ML Kit 条码扫描
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // 对外分发：ACRA 崩溃上报（HTTP Sender，自建 Relay 接收端）
    // 注：排除 auto-service 传递的 Guava，避免与 CameraX 的 ListenableFuture 冲突
    implementation("ch.acra:acra-http:5.11.3") {
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
