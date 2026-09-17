plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
}

val appPackageName: String by rootProject.extra

android {
    namespace = "$appPackageName.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        vectorDrawables { useSupportLibrary = true }
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
    api(libs.androidx.appcompat)
    api(libs.material)
}
