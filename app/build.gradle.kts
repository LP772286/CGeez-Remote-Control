plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cgreez.remote.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cgreez.remote.control"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "6.6"
    }
    // A fixed key checked into the repo, so every build is signed the same way and a new
    // APK installs over the old one without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
