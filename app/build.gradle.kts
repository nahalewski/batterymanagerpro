plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ben.ankerbattery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ben.ankerbattery"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// No Compose/AndroidX dependencies. This build intentionally stays on JVM 1.8
// because CodeAssist is forcing its Kotlin compiler to that target.
dependencies { }
