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
        versionCode = 10
        versionName = "1.0.0"

        // Built for Pixel phones (ARM64).
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Models are read whole into memory; keep them uncompressed in the APK.
    androidResources { noCompress += "onnx" }
}

val onnxRuntime = "1.31.1"

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntime")

    // Unit tests run the same models on the build machine's JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
}
