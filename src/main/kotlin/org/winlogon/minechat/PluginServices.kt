package org.winlogon.minechat

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
import org.winlogon.minechat.storage.BanStorage
import org.winlogon.minechat.storage.ClientStorage
import org.winlogon.minechat.storage.LinkCodeStorage
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Interface for plugin services and dependencies.
 *
 * This interface provides a centralized way to access all plugin services,
 * including storage, configuration, and connected client management.
 * It is implemented by [org.winlogon.minechat.MineChatPlugin].
 */
interface PluginServices {
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
    fun broadcastToClients(packetType: Int, payload: PacketPayload)
}
