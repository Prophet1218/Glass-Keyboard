plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.glasskeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.glasskeyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "1.4.1"
    }

    // Fixed signing key kept in the project, so every build (GitHub or Android Studio)
    // can be installed over the previous one.
    signingConfigs {
        create("glass") {
            storeFile = file("glass.keystore")
            storePassword = "glasskeyboard"
            keyAlias = "glass"
            keyPassword = "glasskeyboard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("glass")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glass")
        }
    }

    // The word lists are memory-mapped straight from the APK, so they must stay uncompressed.
    androidResources {
        noCompress += listOf("dict")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
