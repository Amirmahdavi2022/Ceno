plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0-dev"

android {
    namespace = "xyz.amjmc.sayeh"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.amjmc.sayeh"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // The patched engine is built for arm64 only (almost every phone of the last years).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS") ?: "sayeh"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

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
    // Ouinet 1.6.11 built from source with engine/raw-tunnel.patch
    // (downloaded by CI from the engine-1.6.11-jmc1 release into app/libs).
    implementation(files("libs/ouinet-jmc.aar"))
    // The engine needs these at runtime but doesn't declare them.
    implementation("com.getkeepsafe.relinker:relinker:1.4.5")
    implementation("androidx.annotation:annotation:1.9.1")
}
