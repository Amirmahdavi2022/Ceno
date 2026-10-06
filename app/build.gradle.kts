plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.amjmc.cenoprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.amjmc.cenoprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // One APK per CPU so the download stays small; universal one as a fallback.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // Same engine version the official Ceno browser ships.
    implementation("ie.equalit.ouinet:ouinet-omni:1.6.11")
}
