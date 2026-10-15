plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nativeAar = file("libs/rahacore.aar")

android {
    namespace = "ir.raha.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.raha.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
    }

    signingConfigs {
        // Fixed sideload key so every build installs over the previous one.
        // Swap for a private key (env vars) before any store release.
        create("sideload") {
            storeFile = rootProject.file(System.getenv("RAHA_KEYSTORE") ?: "keystore/raha-sideload.jks")
            storePassword = System.getenv("RAHA_KEYSTORE_PASSWORD") ?: "rahasideload"
            keyAlias = System.getenv("RAHA_KEY_ALIAS") ?: "raha"
            keyPassword = System.getenv("RAHA_KEY_PASSWORD") ?: "rahasideload"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures { buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Built by scripts/build-rahacore-android.sh (brokerapi verifier + Xray
    // engine). Not kept in git. The app cannot work without it, so fail loudly.
    if (!nativeAar.exists()) {
        throw GradleException("app/libs/rahacore.aar is missing — run scripts/build-rahacore-android.sh first")
    }
    implementation(files(nativeAar))
}
