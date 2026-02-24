pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom-remap") version settings.extra["loom_version"].toString()
        id("org.jetbrains.kotlin.jvm") version "2.3.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    }
}
