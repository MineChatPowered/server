plugins {
    id("com.gradleup.shadow") version "9.3.0"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
}

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
    } catch (_: Exception) {
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
val projectName = pluginName

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
    compileOnly(libs.caffeine)
    compileOnly(libs.zstd.jni)

    compileOnly(libs.paper.api)
    compileOnly(libs.kotlin.reflect)
    compileOnly(libs.asynccraftr)
    compileOnly(libs.lamp.common)
    compileOnly(libs.lamp.bukkit)
    compileOnly(libs.bouncycastle.bcprov)
    compileOnly(libs.bouncycastle.bcpkix)

    implementation(libs.kaml)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    compileOnly(libs.sqlite.jdbc)

    implementation(libs.kotlinx.serialization.cbor)

    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.paper.api.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
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
    // TODO: add something like this for all impl() deps:
    // relocate("io.papermc.lib", "org.winlogon.minechat.shadow.paperlib")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
