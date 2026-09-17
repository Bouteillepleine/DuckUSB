import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

val appPackageName: String by rootProject.extra
val moduleVersionName: String by rootProject.extra
val moduleVersionCode: Int by rootProject.extra

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val signStoreFile: String? = keystoreProperties.getProperty("storeFile") ?: System.getenv("DUCKUSB_STORE_FILE")
val signStorePassword: String? = keystoreProperties.getProperty("storePassword") ?: System.getenv("DUCKUSB_STORE_PASSWORD")
val signKeyAlias: String? = keystoreProperties.getProperty("keyAlias") ?: System.getenv("DUCKUSB_KEY_ALIAS")
val signKeyPassword: String? = keystoreProperties.getProperty("keyPassword") ?: System.getenv("DUCKUSB_KEY_PASSWORD")
val hasSigning = signStoreFile != null && signStorePassword != null && signKeyAlias != null && signKeyPassword != null

android {
    namespace = "$appPackageName.zygisk"
    compileSdk = 36

    defaultConfig {
        applicationId = "$appPackageName.zygisk"
        minSdk = 29
        targetSdk = 36
        versionCode = moduleVersionCode
        versionName = moduleVersionName
        vectorDrawables { useSupportLibrary = true }
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
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
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
    implementation(projects.common)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
