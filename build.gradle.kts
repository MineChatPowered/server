import java.text.SimpleDateFormat
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        maven { url = uri("https://download.objectbox.io/maven") }
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:3.8.0")
    }
}

plugins {
    id("com.gradleup.shadow") version "8.3.6"
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

apply(plugin = "io.objectbox")

group = "org.winlogon.minechat"

fun getLatestGitTag(): String? {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor()
        if (process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

val shortVersion: String? = if (project.hasProperty("ver")) {
    project.property("ver").toString()
} else {
    getLatestGitTag()
}

val version: String = when {
    shortVersion.isNullOrEmpty() -> "0.0.0-SNAPSHOT"
    shortVersion.contains("-RC-") -> shortVersion.substringBefore("-RC-") + "-SNAPSHOT"
    else -> if (shortVersion.startsWith("v")) {
        shortVersion.substring(1).uppercase()
    } else {
        shortVersion.uppercase()
    }
}

val pluginName = rootProject.name
val pluginVersion = version
val pluginPackage = project.group.toString()
val projectName = rootProject.name

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
        content {
            includeModule("io.papermc.paper", "paper-api")
            includeModule("net.md-5", "bungeecord-chat")
        }
    }
    maven {
        name = "minecraft"
        url = uri("https://libraries.minecraft.net")
        content {
            includeModule("com.mojang", "brigadier")
        }
    }

    maven {
        url = uri("https://maven.winlogon.org/releases")
    }

    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT")
    compileOnly("org.winlogon:asynccraftr:0.1.0")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.0")
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("io.objectbox:objectbox-kotlin:3.8.0")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:2.1.10")
    compileOnly("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.1")
    compileOnly("com.github.luben:zstd-jni:1.5.6-1")
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:" + libs.versions.kotlinx.serialization.json.get())
    implementation("com.charleskorn.kaml:kaml:" + libs.versions.kaml.get())

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching("**/paper-plugin.yml") {
        expand(
            "NAME" to pluginName,
            "VERSION" to pluginVersion,
            "PACKAGE" to pluginPackage
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("io.papermc.lib", "shadow.io.papermc.paperlib")
    minimize()
}

// Disable jar and replace with shadowJar
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Utility tasks
tasks.register("printProjectName") {
    doLast {
        println(projectName)
    }
}

var shadowJarTask = tasks.shadowJar.get()
tasks.register("release") {
    dependsOn(tasks.build)
    doLast {
        if (!version.endsWith("-SNAPSHOT")) {
            shadowJarTask.archiveFile.get().asFile.renameTo(
                file("${layout.buildDirectory.get()}/libs/${rootProject.name}.jar")
            )
        }
    }
}
