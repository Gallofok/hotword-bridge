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
        versionCode = 11
        versionName = "1.0.1"

        // Built for Pixel phones (ARM64).
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // CI signs with one permanent key (from repository secrets) so that every
    // build can be installed over the previous one. Without the key, e.g. on a
    // developer machine, Gradle falls back to the local debug key.
    val keystorePath = System.getenv("HWB_KEYSTORE_FILE").orEmpty()
    if (keystorePath.isNotEmpty()) {
        signingConfigs {
            create("stable") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("HWB_KEYSTORE_PASSWORD")
                keyAlias = "hotwordbridge"
                keyPassword = System.getenv("HWB_KEYSTORE_PASSWORD")
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
        }
    }

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
