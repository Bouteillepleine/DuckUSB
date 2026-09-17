plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "com.strawing.duckprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.strawing.duckprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
