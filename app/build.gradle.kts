plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.gallofok.hotwordbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.gallofok.hotwordbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        // Initial test build is for Pixel 7 (ARM64).
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Prepared by scripts/prepare-sherpa.sh; no external API key required.
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
