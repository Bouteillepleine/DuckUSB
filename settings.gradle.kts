import kotlin.io.path.Path

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    versionCatalogs {
        create("androidvmtools") {
            from(
                files(
                    Path(rootDir.path, "external", "AndroidVMTools", "gradle", "libs.versions.toml")
                )
            )
        }
    }
}

rootProject.name = "DuckUSB-Zygisk"

include(":common", ":zygote")
