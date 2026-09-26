plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets a higher version number automatically, so the TV accepts it as an update.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.tvweb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvweb"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Always sign with the same key. Without this, every build would be signed differently
    // and the TV would refuse to install a new version over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("tvwebremote.keystore")
            storePassword = "tvwebremote"
            keyAlias = "tvwebremote"
            keyPassword = "tvwebremote"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
