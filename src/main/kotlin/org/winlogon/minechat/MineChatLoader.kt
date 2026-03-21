@file:Suppress("UnstableApiUsage") // Loader API is "experimental"

package org.winlogon.minechat

import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver

import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository

class MineChatLoader : PluginLoader {

    private val versions = mapOf(
        "caffeine" to "3.2.0",
        "zstd-jni" to "1.5.6-1",
        "objectbox" to "3.8.0",
        "asynccraftr" to "0.1.0",
        "lamp" to "4.0.0-rc.16",
        "bouncycastle" to "1.78.1"
    )

    override fun classloader(classpathBuilder: PluginClasspathBuilder) {
        val resolver = MavenLibraryResolver()

        val dependencies = arrayOf(
            library("com.github.ben-manes.caffeine", "caffeine", versions["caffeine"]!!),
            library("com.github.luben", "zstd-jni", versions["zstd-jni"]!!),
            library("io.objectbox", "objectbox-kotlin", versions["objectbox"]!!),
            library("org.winlogon", "asynccraftr", versions["asynccraftr"]!!),
            library("io.github.revxrsal", "lamp.common", versions["lamp"]!!),
            library("io.github.revxrsal", "lamp.bukkit", versions["lamp"]!!),
            library("org.bouncycastle", "bcprov-jdk18on", versions["bouncycastle"]!!),
            library("org.bouncycastle", "bcpkix-jdk18on", versions["bouncycastle"]!!)
        )

        val repositories = arrayOf(
            repo("maven-central", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR),
            repo("objectbox", "https://download.objectbox.io/maven")
        )

        for (dependency in dependencies) {
            resolver.addDependency(dependency)
        }

        for (repository in repositories) {
            resolver.addRepository(repository)
        }

        classpathBuilder.addLibrary(resolver)
    }

    private fun library(group: String, artifact: String, version: String): Dependency =
        Dependency(DefaultArtifact("$group:$artifact:$version"), null)

    private fun repo(id: String, url: String): RemoteRepository =
        RemoteRepository.Builder(id, "default", url).build()
}
