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
    compileSdk = 36
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
        versionCode = 10
        versionName = "1.4.0"
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

    // The framework reads the module's entry points out of META-INF/xposed/. AGP would
    // otherwise drop or pick one of the duplicate META-INF paths; merging keeps all four
    // (module.prop, java_init.list, native_init.list, scope.list) verbatim in the APK.
    packaging {
        resources { merges += "META-INF/xposed/*" }
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
    // Modern Xposed (libxposed) API 101. The legacy de.robv.android.xposed:api:82 is gone:
    // a module is either legacy or modern, never both, because the framework picks the entry
    // point from what the APK declares (assets/xposed_init vs META-INF/xposed/java_init.list).
    // 101 is deliberate rather than 102 — 102 forbids calling legacy APIs at all, while 101
    // still permits it, which keeps an escape hatch if something here turns out to need one.
    compileOnly("io.github.libxposed:api:101.0.1")
    // App-side counterpart: binds the framework service so the UI can read its own LSPosed
    // scope and share preferences with the hook. This is what makes the scope reporting in
    // MainActivity honest — under the legacy API the UI could not see its own scope at all.
    implementation("io.github.libxposed:service:101.0.0")
}
