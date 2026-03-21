@file:Suppress("UnstableApiUsage")

package org.winlogon.minechat

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType

import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.winlogon.minechat.commands.MinechatCommands
import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.LinkCode
import revxrsal.commands.Lamp
import revxrsal.commands.bukkit.BukkitLamp

class CommandRegister(private val services: PluginServices) : Listener {

    private var lamp: Lamp<*>? = null

    fun registerCommands() {
        registerLampCommands()
        registerLegacyCommands()
    }

    private fun registerLampCommands() {
        lamp = BukkitLamp.builder(services.pluginInstance)
            .build()
            .also { lamp ->
                lamp.register(MinechatCommands(services))
            }
    }

    private fun registerLegacyCommands() {
        val linkCommand = Commands.literal("link")
            .requires { sender -> sender.executor is Player }
            .executes { ctx ->
                val sender = ctx.source.sender as Player
                this.generateAndSendLinkCode(sender)
                Command.SINGLE_SUCCESS
            }
            .build()

        val reloadCommand = Commands.literal("minechat-reload")
            .requires { sender -> sender.sender.hasPermission(services.permissions["reload"]!!) }
            .executes { ctx ->
                services.reloadConfigAndDependencies()
                ctx.source.sender.sendMessage(Component.text("MineChat config reloaded.").color(NamedTextColor.GREEN))

                val infoMsg = "MineChat configuration has been reloaded by an administrator."
                val chatPayload = ChatMessagePayload(
                    format = "commonmark",
                    content = "<gradient:${ChatGradients.INFO.first}:${ChatGradients.INFO.second}>$infoMsg</gradient>"
                )
                services.broadcastToClients(PacketTypes.CHAT_MESSAGE, chatPayload)
                Command.SINGLE_SUCCESS
            }
            .build()

        services.pluginInstance.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(linkCommand, "Generate a MineChat link code for your account.")
            registrar.register(reloadCommand, "Reload the MineChat configuration and services.")
        }
    }

    fun generateAndSendLinkCode(player: Player) {
        val code = services.generateRandomLinkCode()

        val link = LinkCode(
            code = code,
            minecraftUuid = player.uniqueId,
            minecraftUsername = player.name,
            expiresAt = System.currentTimeMillis() + (services.mineChatConfig.expiryCodeMinutes * 60_000L)
        )
        services.linkCodeStorage.add(link)

        val codeComponent = Component.text(code, NamedTextColor.DARK_AQUA)
        val timeComponent = Component.text("${services.mineChatConfig.expiryCodeMinutes} minutes", NamedTextColor.DARK_GREEN)
        player.sendRichMessage(
            "<gray>Your link code is: </gray><code>. Use it in the client within <deadline>",
            Placeholder.component("code", codeComponent),
            Placeholder.component("deadline", timeComponent)
        )
    }

    fun getClientConnection(username: String): ClientConnection? {
        return services.connectedClients.find { it.getClient()?.minecraftUsername == username }
    }
}
