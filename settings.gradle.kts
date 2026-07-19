pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API (compileOnly)
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "DuckADB"
include(":app")
