plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
}

val appPackageName: String by rootProject.extra

android {
    namespace = "$appPackageName.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
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
