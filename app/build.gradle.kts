plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apumsw1.ultraloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apumsw1.ultraloader"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "3.1"
    }

    val debugKeystoreFile = file("${rootDir}/debug.keystore")

    signingConfigs {
        if (debugKeystoreFile.exists()) {
            create("debugConfig") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            storeFile = file(
                System.getenv("ANDROID_KEYSTORE_PATH")
                    ?: "release-keystore.jks"
            )
            storePassword =
                System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
            keyAlias =
                System.getenv("ANDROID_KEY_ALIAS") ?: ""
            keyPassword =
                System.getenv("ANDROID_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

