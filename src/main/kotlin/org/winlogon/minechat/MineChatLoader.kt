@file:Suppress("UnstableApiUsage") // Loader API is "experimental"

package org.winlogon.minechat

import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver

import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository

class MineChatLoader : PluginLoader {
    override fun classloader(classpathBuilder: PluginClasspathBuilder) {
    	val caffeineVersion = "3.2.0"
        val resolver = MavenLibraryResolver()

        val dependencies = arrayOf(
            library("com.github.ben-manes.caffeine", "caffeine", caffeineVersion),
            library("com.github.luben", "zstd-jni", "1.5.6-1")
        )

        val repositories = arrayOf(
            repo("maven-central", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
        )

        for (dependency in dependencies) {
            resolver.addDependency(dependency)
        }

        for (repository in repositories) {
            resolver.addRepository(repository)
        }

        classpathBuilder.addLibrary(resolver)
    }

    fun library(group: String, artifact: String, version: String): Dependency =
        Dependency(DefaultArtifact("$group:$artifact:$version"), null)

    fun repo(id: String, url: String): RemoteRepository =
        RemoteRepository.Builder(id, "default", url).build()
}
