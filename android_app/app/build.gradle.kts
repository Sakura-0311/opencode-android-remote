plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.opencode.android"
    compileSdk = 34

    // B-12: versionCode 随 versionName 自动递增（1.5.0 -> 10500）
    val appVersionName = "1.5.0"
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
        create("release") {
            // B-12: 正式签名 keystore 随仓库提交，保证 CI 每次构建签名一致（否则更新安装会报签名不匹配）
            // 密码可通过环境变量 RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_PASSWORD 覆盖
            storeFile = file("release.keystore")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "***REMOVED***"
            keyAlias = "opencode-release"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "***REMOVED***"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

    // Debugging UI Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
