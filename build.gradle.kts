plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.kotlin) apply false
}

val appPackageName by extra("com.strawing.duckusb")
val moduleId by extra("duckusb_zygisk")
val moduleVersionName by extra("2.1.6")
val moduleVersionCode by extra(29)

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
