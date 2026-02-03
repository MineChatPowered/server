package org.winlogon.minechat

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentLinkedQueue

interface MineChatPluginServices {
    val pluginInstance: JavaPlugin
    val linkCodeStorage: LinkCodeStorage
    val clientStorage: ClientStorage
    val banStorage: BanStorage
    val mineChatConfig: MineChatConfig
    val permissions: Map<String, Permission>
    val miniMessage: MiniMessage
    val connectedClients: ConcurrentLinkedQueue<ClientConnection>

    fun reloadConfigAndDependencies()
    fun generateRandomLinkCode(): String
}
