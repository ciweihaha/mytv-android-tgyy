import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Round 4: AGP 9.x built-in Kotlin — do not apply
    // org.jetbrains.kotlin.android (removed by AGP 9.0).
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "top.yogiczy.mytv"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.yogiczy.mytv"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "2.2.17"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // opt: 只保留 ARM 两个 ABI。x86_64 的 libffmpegJNI.so 有
            // 1,381,952 字节，.so 在 APK 里不压缩存放，等于白占
            // release 包约 21%；而 x86_64 设备（模拟器、x86 盒子/
            // Chromebook）极少，且缺了该 ABI 的 .so 时 Media3 会
            // 捕获 UnsatisfiedLinkError、回落到系统解码器，不会崩。
            // x86 早已不在此列；32 位 ARM 电视仍多，armeabi-v7a 保留。
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // P3：EventLogger 只在 debug 构建挂载，需要 BuildConfig.DEBUG
        buildConfig = true
    }
    testOptions {
        // 单元测试跑在本机 JVM 上，android.jar 里的 Log 等方法是桩，
        // 默认一调就抛错；让桩返回默认值，生产代码里的日志调用在测
        // 试里不再炸。只影响测试，不影响打包出来的 App
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            storeFile =
                file(System.getenv("KEYSTORE") ?: keystoreProperties["storeFile"] ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
            keyPassword =
                System.getenv("KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

// Round 4 fix: AGP 9 removed the kotlinOptions DSL (it came from the Kotlin
// Gradle plugin, which AGP 9 no longer allows applying). JVM target is now
// set via the built-in Kotlin extension's compilerOptions.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.material.icons.extended)

    // TV Compose
    implementation(libs.androidx.tv.material)

    // 播放器
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.datasource.okhttp)

    // 序列化
    implementation(libs.kotlinx.serialization)

    // 网络请求
    implementation(libs.okhttp)
    implementation(libs.androidasync)

    // 二维码
    implementation(libs.qrose)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    testImplementation(libs.junit)
    // 单元测试：parseFromXml 走 XmlPullParserFactory，测试里用真
    // KXmlParser（只进测试 classpath，不进正式包）
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}