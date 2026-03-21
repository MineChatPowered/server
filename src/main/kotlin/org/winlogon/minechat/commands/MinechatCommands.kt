package org.winlogon.minechat.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

import org.winlogon.minechat.ChatGradients
import org.winlogon.minechat.ChatMessagePayload
import org.winlogon.minechat.ClientConnection
import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.LinkCode
import org.winlogon.minechat.entities.Mute
import org.winlogon.minechat.ModerationAction
import org.winlogon.minechat.ModerationScope
import org.winlogon.minechat.PacketTypes
import org.winlogon.minechat.PluginServices

import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Default
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Named
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.bukkit.actor.BukkitCommandActor
import revxrsal.commands.bukkit.annotation.CommandPermission

/**
 * MineChat moderation commands using Lamp command framework.
 */
@Command("minechat")
@Description("MineChat server moderation commands")
class MinechatCommands(private val services: PluginServices) {
    val miniMessage = services.miniMessage

    @Subcommand("ban")
    @Description("Ban a player from MineChat")
    @CommandPermission("minechat.ban")
    fun ban(
        actor: BukkitCommandActor,
        player: String,
        @Default("Banned by an operator.") @Named("reason") reason: String?
    ) {
        val client = services.clientStorage.find(null, player)
        if (client == null) {
            actor.reply { Component.text("Player not found.", NamedTextColor.RED) }
            return
        }

        val ban = Ban(
            minecraftUsername = player,
            reason = reason
        )
        services.banStorage.add(ban)

        getClientConnection(player)?.sendModerationAndDisconnect(
            ModerationAction.BAN,
            ModerationScope.ACCOUNT,
            reason,
            null
        )

        actor.reply { Component.text("Banned $player from MineChat.", NamedTextColor.GREEN) }
    }

    @Subcommand("unban")
    @Description("Remove a ban from a player")
    @CommandPermission("minechat.unban")
    fun unban(actor: BukkitCommandActor, player: String) {
        services.banStorage.remove(null, player)
        actor.reply { Component.text("Unbanned $player from MineChat.", NamedTextColor.GREEN) }
    }

    @Subcommand("mute")
    @Description("Mute a player in MineChat")
    @CommandPermission("minechat.mute")
    fun mute(
        actor: BukkitCommandActor,
        player: String,
        @Default("5") durationMinutes: Int,
        @Default("Muted by an operator.") @Named("reason") reason: String?
    ) {
        val expiresAt = System.currentTimeMillis() + (durationMinutes.toLong() * 60 * 1000)

        val mute = Mute(
            minecraftUsername = player,
            reason = reason,
            expiresAt = expiresAt
        )
        services.muteStorage.add(mute)

        getClientConnection(player)?.sendModeration(
            ModerationAction.MUTE,
            ModerationScope.CLIENT,
            reason,
            durationMinutes * 60
        )

        actor.reply {
            Component.text("Muted $player for $durationMinutes minutes in MineChat.", NamedTextColor.GREEN)
        }
    }

    @Subcommand("unmute")
    @Description("Remove a mute from a player")
    @CommandPermission("minechat.mute")
    fun unmute(actor: BukkitCommandActor, player: String) {
        services.muteStorage.remove(player)
        actor.reply { Component.text("Unmuted $player in MineChat.", NamedTextColor.GREEN) }
    }

    @Subcommand("warn")
    @Description("Warn a player in MineChat")
    @CommandPermission("minechat.warn")
    fun warn(
        actor: BukkitCommandActor,
        player: String,
        @Default("Warning issued by an operator.") @Named("reason") reason: String?
    ) {
        getClientConnection(player)?.sendModeration(
            ModerationAction.WARN,
            ModerationScope.CLIENT,
            reason,
            null
        )

        actor.reply { Component.text("Warned $player in MineChat.", NamedTextColor.GREEN) }
    }

    @Subcommand("kick")
    @Description("Kick a player from MineChat")
    @CommandPermission("minechat.kick")
    fun kick(
        actor: BukkitCommandActor,
        player: String,
        @Default("Kicked by an operator.") @Named("reason") reason: String?
    ) {
        val clientConnection = getClientConnection(player)
        if (clientConnection == null) {
            actor.reply { Component.text("Player not found or not connected via MineChat.", NamedTextColor.RED) }
            return
        }

        clientConnection.sendModerationAndDisconnect(
            ModerationAction.KICK,
            ModerationScope.CLIENT,
            reason,
            null
        )

        actor.reply { Component.text("Kicked $player from MineChat.", NamedTextColor.GREEN) }
    }

    @Subcommand("link")
    @Description("Generate a link code for your MineChat client")
    fun link(actor: BukkitCommandActor) {
        val player = actor.asPlayer()!!

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
        actor.reply {
            miniMessage.deserialize(
                "<gray>Your link code is <code>. Use it within <deadline></gray>",
                Placeholder.component("code", codeComponent),
                Placeholder.component("deadline", timeComponent)
            )
        }
    }

    @Subcommand("reload")
    @Description("Reload the MineChat configuration")
    @CommandPermission("minechat.reload")
    fun reload(actor: BukkitCommandActor) {
        services.reloadConfigAndDependencies()
        actor.reply { Component.text("MineChat config reloaded.", NamedTextColor.GREEN) }

        val infoMsg = "MineChat configuration has been reloaded by an administrator."
        val chatPayload = ChatMessagePayload(
            format = "commonmark",
            content = "<gradient:${ChatGradients.INFO.first}:${ChatGradients.INFO.second}>$infoMsg</gradient>"
        )
        services.broadcastToClients(PacketTypes.CHAT_MESSAGE, chatPayload)
    }

    fun getClientConnection(username: String): ClientConnection? {
        return services.connectedClients.find { it.getClient()?.minecraftUsername == username }
    }
}
