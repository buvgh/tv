import java.util.Properties
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

fun localOrEnv(key: String): String? {
    return localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: System.getenv(key)
}

android {
    namespace = "com.example.myapplicationlibretv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplicationlibretv"
        minSdk = 24
        targetSdk = 35
        versionCode = 92
        versionName = "1.8.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = localOrEnv("RELEASE_STORE_FILE")
    val releaseStorePassword = localOrEnv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localOrEnv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localOrEnv("RELEASE_KEY_PASSWORD")

    signingConfigs {
        if (
            !releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    kotlin {
        jvmToolchain(21)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // NanoHTTPD
    implementation(libs.nanohttpd)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    // Coil (Image Loading)
    implementation(libs.coil.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 自动打包并复制到桌面的 Gradle 任务
tasks.register<Copy>("copyReleaseApkToDesktop") {
    group = "build"
    description = "编译并复制发布版 APK 到桌面"
    
    val desktopPath = System.getProperty("user.home") + "/Desktop"
    
    // 确保在编译完成后执行
    dependsOn("assembleRelease")
    
    from("build/outputs/apk/release/app-release.apk")
    into(desktopPath)
    rename { "枫林晚TV-v${android.defaultConfig.versionName}.apk" }
    
    doLast {
        println("✅ 打包成功！文件已保存至桌面：$desktopPath")
    }
}
