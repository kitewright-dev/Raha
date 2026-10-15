plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.raha.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.raha.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Generated locally by scripts/build-brokerbridge-android.sh. It embeds
    // the pinned OpenRung brokerapi verifier; generated binaries are not kept
    // in git.
    if (file("libs/brokerbridge.aar").exists()) {
        implementation(files("libs/brokerbridge.aar"))
    }
}
