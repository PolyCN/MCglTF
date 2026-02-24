import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("mod_version")!!
group = property("maven_group")!!

base {
    archivesName.set(property("archives_base_name").toString())
}

repositories {
    flatDir { dirs("libs") }
}
dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    include("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modCompileOnly(files("libs/iris-fabric-1.10.5+mc1.21.11.jar"))
    implementation("de.javagl:jgltf-model:2.0.4")
    include("de.javagl:jgltf-model:2.0.4")
    implementation("de.javagl:jgltf-impl-v2:2.0.4")
    include("de.javagl:jgltf-impl-v2:2.0.4")
    implementation("de.javagl:jgltf-impl-v1:2.0.4")
    include("de.javagl:jgltf-impl-v1:2.0.4")
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
