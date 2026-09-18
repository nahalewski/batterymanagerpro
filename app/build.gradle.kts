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
        versionCode = 12
        versionName = "1.2.0-beta.1"
    }

    buildTypes {
        release {
            // Beta builds are signed with the debug key so they can be side-loaded.
            // Add a real keystore before any store submission.
            signingConfig = signingConfigs.getByName("debug")
        }
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
