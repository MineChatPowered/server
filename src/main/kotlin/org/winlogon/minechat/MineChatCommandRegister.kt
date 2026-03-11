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
import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.LinkCode

class MineChatCommandRegister(private val services: MineChatPluginServices) : Listener {
    fun registerCommands() {
        val linkCommand = Commands.literal("link")
            .requires { sender -> sender.executor is Player }
            .executes { ctx ->
                val sender = ctx.source.sender as Player
                this.generateAndSendLinkCode(sender)
                Command.SINGLE_SUCCESS
            }
            .build()

        val reloadCommand = Commands.literal("mchatreload")
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

        val banCommand = Commands.literal("minechat-ban")
            .requires { sender -> sender.sender.hasPermission(services.permissions["ban"]!!) }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    val client = services.clientStorage.find(null, playerName)
                    if (client == null) {
                        ctx.source.sender.sendMessage(Component.text("Player not found.").color(NamedTextColor.RED))
                        return@executes 0
                    }
                    val reason = "Banned by an operator."
                    val ban = Ban(minecraftUsername = playerName, reason = reason)
                    services.banStorage.add(ban)
                    
                    // Notify other clients
                    val modPayload = ModerationPayload(
                        action = ModerationAction.BAN,
                        scope = ModerationScope.ACCOUNT,
                        reason = reason
                    )
                    services.broadcastToClients(PacketTypes.MODERATION, modPayload)
                    
                    // Disconnect the client if online
                    getClientConnection(playerName)?.disconnect(reason)
                    
                    ctx.source.sender.sendMessage(Component.text("Banned $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        val unbanCommand = Commands.literal("minechat-unban")
            .requires { sender -> sender.sender.hasPermission("minechat.unban") }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    services.banStorage.remove(null, playerName)
                    ctx.source.sender.sendMessage(Component.text("Unbanned $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        val kickCommand = Commands.literal("minechat-kick")
            .requires { sender -> sender.sender.hasPermission("minechat.kick") }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    val clientConnection = this.getClientConnection(playerName)
                    if (clientConnection == null) {
                        ctx.source.sender.sendMessage(Component.text("Player not found or not connected via MineChat.").color(NamedTextColor.RED))
                        return@executes 0
                    }
                    val reason = "Kicked by an operator."
                    
                    // Notify other clients
                    val modPayload = ModerationPayload(
                        action = ModerationAction.KICK,
                        scope = ModerationScope.CLIENT,
                        reason = reason
                    )
                    services.broadcastToClients(PacketTypes.MODERATION, modPayload)
                    
                    clientConnection.disconnect(reason)
                    ctx.source.sender.sendMessage(Component.text("Kicked $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        services.pluginInstance.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(linkCommand)
            registrar.register(reloadCommand)
            registrar.register(banCommand)
            registrar.register(unbanCommand)
            registrar.register(kickCommand)
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
