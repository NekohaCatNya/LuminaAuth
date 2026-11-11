plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.luminaauth"
    compileSdk = 37
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.luminaauth"
        minSdk = 29
        multiDexEnabled = true
        targetSdk = 36
        versionCode = 211
        versionName = "1.1.4"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../schoolautologin-release.keystore")
            storePassword = "schoolautologin123"
            keyAlias = "schoolautologin"
            keyPassword = "schoolautologin123"
        }
    }

    buildTypes {
        release {
            // 启用 R8 压缩/混淆/资源缩减；类名映射见根目录 R8-类名映射.md
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // 压缩 DEX（minSdk>=28 时 AGP 默认未压缩，导致 APK 偏大）
        // 仅压缩 dex；原生库 .so 仍保持 Stored 页对齐，不影响安装
        dex {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    lint {
        checkReleaseBuilds = false
    }
}


dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation(files("libs/miuix-preference-android-0.9.4.aar"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")
    // 开源多线程下载引擎（分片并发 + 断点续传）
    implementation("com.liulishuo.filedownloader:library:1.7.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
