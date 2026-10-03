plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// N-1: 签名密钥永不进仓库。按优先级读取：
//   1) 环境变量 RELEASE_KEYSTORE_FILE / RELEASE_KEYSTORE_PASSWORD /
//      RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD（CI 从 Secrets 注入）
//   2) android_app/keystore.properties（本地开发，已 gitignore）
//   缺失时降级为 debug 签名并打警告，保证 CI 不中断；该包不可对外分发。
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
if (!hasReleaseSigning) {
    logger.warn("[N-1] 未配置 release 签名密钥，本次构建的 release 包将使用 debug 签名，不可对外分发")
}

android {
    namespace = "com.opencode.android"
    compileSdk = 34

    // B-12: versionCode 随 versionName 自动递增（2.0.0 -> 20000）
    val appVersionName = "2.1.0"
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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // N-1: 有密钥才用 release 签名，否则降级 debug 签名（CI 可用，不可分发）
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
}
