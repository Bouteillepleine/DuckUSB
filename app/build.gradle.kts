import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Signing inputs: prefer a local, git-ignored key.properties for developer builds; otherwise
// fall back to environment variables injected from CI secrets. The keystore is NEVER committed.
val signStoreFile: String? = keystoreProperties.getProperty("storeFile") ?: System.getenv("DUCKUSB_STORE_FILE")
val signStorePassword: String? = keystoreProperties.getProperty("storePassword") ?: System.getenv("DUCKUSB_STORE_PASSWORD")
val signKeyAlias: String? = keystoreProperties.getProperty("keyAlias") ?: System.getenv("DUCKUSB_KEY_ALIAS")
val signKeyPassword: String? = keystoreProperties.getProperty("keyPassword") ?: System.getenv("DUCKUSB_KEY_PASSWORD")
val hasSigning: Boolean =
    signStoreFile != null && signStorePassword != null && signKeyAlias != null && signKeyPassword != null

android {
    compileSdk = 35
    namespace = "com.strawing.duckusb"

    // IDuckService.aidl — the system_server <-> UI channel.
    buildFeatures { aidl = true }
    // CI pins 27.2.12479018 (installed via sdkmanager). Local builds can override with
    // -PduckusbNdk=<installed-version> without touching the committed CI value.
    ndkVersion = (findProperty("duckusbNdk") as String?) ?: "27.2.12479018"

    defaultConfig {
        applicationId = "com.strawing.duckusb"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.0"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    compileOnly("de.robv.android.xposed:api:82")
}
