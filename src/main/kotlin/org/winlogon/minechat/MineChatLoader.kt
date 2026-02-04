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
        val resolver = MavenLibraryResolver().apply {
            addDependency(Dependency(DefaultArtifact("com.github.ben-manes.caffeine:caffeine:$caffeineVersion"), null))
            addRepository(
                RemoteRepository.Builder("maven-central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                    .build()
            )
        }
        classpathBuilder.addLibrary(resolver)
    }
}
