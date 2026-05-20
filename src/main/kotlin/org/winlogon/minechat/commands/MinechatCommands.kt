package org.winlogon.minechat.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

import org.winlogon.minechat.ChatGradients
import org.winlogon.minechat.ClientConnection
import org.winlogon.minechat.ModerationAction
import org.winlogon.minechat.ModerationScope
import org.winlogon.minechat.PluginServices

import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Default
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Named
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.annotation.Switch
import revxrsal.commands.bukkit.actor.BukkitCommandActor
import revxrsal.commands.bukkit.annotation.CommandPermission

@Command("minechat")
@Description("MineChat server moderation commands")
class MinechatCommands(private val services: PluginServices) {
    private val miniMessage = services.miniMessage
    private val config get() = services.mineChatConfig

@Subcommand("ban")
    @Description("Ban a player from MineChat")
    @CommandPermission("minechat.ban")
    fun ban(
        actor: BukkitCommandActor,
        player: String,
        @Switch("a") @Description("Apply to all clients linked to the player's account") affectAccount: Boolean = false,
        @Default("-1") @Named("duration") @Description("Duration in minutes, -1 for permanent") durationMinutes: Int = -1,
        @Default("") @Named("reason") @Description("Reason for the ban") reason: String?
    ) {
        if (durationMinutes == 0) {
            actor.reply { Component.text("Duration cannot be 0. Use a positive number or -1 for permanent.", NamedTextColor.RED) }
            return
        }

        val clients = services.clientStorage.findByUsername(player)
        if (clients.isEmpty()) {
            actor.reply { Component.text("Player not found.", NamedTextColor.RED) }
            return
        }

        val message = reason?.takeIf { it.isNotBlank() } ?: config.moderationDefaults.banMessage
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT
        val expiresAt = if (durationMinutes == -1) null else System.currentTimeMillis() + (durationMinutes.toLong() * 60 * 1000)

        if (scope == ModerationScope.CLIENT) {
            val singleClient = clients.first()
            services.banStorage.add(
                clientUuid = singleClient.clientUuid,
                minecraftUuid = singleClient.minecraftUuid,
                minecraftUsername = player,
                reason = message,
                expiresAt = expiresAt
            )

            val clientConn = getClientConnectionByUuid(singleClient.clientUuid)
            if (clientConn != null) {
                clientConn.sendModerationAndDisconnect(
                    ModerationAction.BAN,
                    scope,
                    message,
                    null
                )
            }
        } else {
            services.banStorage.add(
                clientUuid = null,
                minecraftUuid = clients.first().minecraftUuid,
                minecraftUsername = player,
                reason = message,
                expiresAt = expiresAt
            )

            val clientConns = getAllClientConnections(player)
            if (clientConns.isNotEmpty()) {
                clientConns.forEach { clientConn ->
                    clientConn.sendModerationAndDisconnect(
                        ModerationAction.BAN,
                        scope,
                        message,
                        null
                    )
                }
            } else {
                actor.reply { Component.text("Note: $player is not connected, but ban applies to their account.", NamedTextColor.YELLOW) }
            }
        }

        val durationText = if (durationMinutes == -1) " permanently" else " for $durationMinutes minutes"
        val scopeText = if (scope == ModerationScope.ACCOUNT) " (all clients)" else ""
        actor.reply { Component.text("Banned $player from MineChat$durationText$scopeText.", NamedTextColor.GREEN) }
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
        @Switch("a") @Description("Apply to all clients linked to the player's account") affectAccount: Boolean = false,
        @Default("") @Named("duration") @Description("Duration in minutes, or 'perm' for permanent") durationMinutes: String?,
        @Default("") @Named("reason") @Description("Reason for the mute") reason: String?
    ) {
        val defaultDuration = config.muteDefaultMinutes
        val duration = when {
            durationMinutes.isNullOrBlank() -> defaultDuration
            durationMinutes == "perm" || durationMinutes == "permanent" -> -1
            else -> durationMinutes.toIntOrNull() ?: defaultDuration
        }

        if (duration <= 0 && duration != -1) {
            actor.reply { Component.text("Invalid duration. Use a positive number or 'perm'.", NamedTextColor.RED) }
            return
        }

        val message = reason?.takeIf { it.isNotBlank() } ?: config.moderationDefaults.muteMessage
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT
        val expiresAt = if (duration == -1) null else System.currentTimeMillis() + (duration.toLong() * 60 * 1000)

        services.muteStorage.add(
            minecraftUsername = player,
            reason = message,
            expiresAt = expiresAt
        )

        val clientConns = getAllClientConnections(player)
        val durationSeconds = if (duration == -1) null else duration * 60
        if (clientConns.isNotEmpty()) {
            clientConns.forEach { clientConn ->
                clientConn.sendModeration(
                    ModerationAction.MUTE,
                    scope,
                    message,
                    durationSeconds
                )
            }
        } else if (scope == ModerationScope.CLIENT) {
            actor.reply { Component.text("Note: $player is not currently connected to MineChat. Mute will be applied when they connect.", NamedTextColor.YELLOW) }
        }

        val durationText = if (duration == -1) "permanently" else "for $duration minutes"
        val scopeText = if (scope == ModerationScope.ACCOUNT) " (all clients)" else ""
        actor.reply { Component.text("Muted $player $durationText$scopeText in MineChat.", NamedTextColor.GREEN) }
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
        @Switch("a") @Description("Apply to all clients linked to the player's account") affectAccount: Boolean = false,
        @Default("") @Named("reason") @Description("Reason for the warning") reason: String?
    ) {
        val message = reason?.takeIf { it.isNotBlank() } ?: config.moderationDefaults.warnMessage
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT

        val clientConns = getAllClientConnections(player)
        if (clientConns.isNotEmpty()) {
            clientConns.forEach { clientConn ->
                clientConn.sendModeration(
                    ModerationAction.WARN,
                    scope,
                    message,
                    null
                )
            }
        } else {
            actor.reply { Component.text("Note: $player is not currently connected to MineChat. Warning cannot be delivered.", NamedTextColor.YELLOW) }
            return
        }

        val scopeText = if (scope == ModerationScope.ACCOUNT) " (all clients)" else ""
        actor.reply { Component.text("Warned $player in MineChat$scopeText.", NamedTextColor.GREEN) }
    }

    @Subcommand("kick")
    @Description("Kick a player from MineChat")
    @CommandPermission("minechat.kick")
    fun kick(
        actor: BukkitCommandActor,
        player: String,
        @Switch("a") @Description("Apply to all clients linked to the player's account") affectAccount: Boolean = false,
        @Default("") @Named("reason") @Description("Reason for the kick") reason: String?
    ) {
        val message = reason?.takeIf { it.isNotBlank() } ?: config.moderationDefaults.kickMessage
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT

        val clientConn = getClientConnection(player)
        if (clientConn == null) {
            actor.reply { Component.text("Player not found or not connected via MineChat.", NamedTextColor.RED) }
            return
        }

        clientConn.sendModerationAndDisconnect(
            ModerationAction.KICK,
            scope,
            message,
            null
        )

        val scopeText = if (scope == ModerationScope.ACCOUNT) " (all clients)" else ""
        actor.reply { Component.text("Kicked $player from MineChat$scopeText.", NamedTextColor.GREEN) }
    }

    @Subcommand("link")
    @Description("Generate a link code for your MineChat client")
    @CommandPermission("minechat.link")
    fun link(actor: BukkitCommandActor) {
        val player = actor.asPlayer()!!

        val code = generateRandomLinkCode()
        services.linkCodeStorage.add(
            code = code.uppercase(),
            minecraftUuid = player.uniqueId,
            minecraftUsername = player.name,
            expiresAt = System.currentTimeMillis() + (config.expiryCodeMinutes * 60_000L)
        )

        val codeComponent = Component.text(code, NamedTextColor.DARK_AQUA)
        val timeComponent = Component.text("${config.expiryCodeMinutes} minutes", NamedTextColor.DARK_GREEN)
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
        val gradientColor = "<gradient:${ChatGradients.INFO.first}:${ChatGradients.INFO.second}>$infoMsg</gradient>"
        val component = miniMessage.deserialize(gradientColor)
        services.broadcastChatMessage("commonmark", gradientColor, component)
    }

    private fun getClientConnection(username: String): ClientConnection? {
        return services.connectedClients.find {
            it.getClient()?.minecraftUsername?.equals(username, ignoreCase = true) == true
        }
    }

    private fun getClientConnectionByUuid(clientUuid: String): ClientConnection? {
        return services.connectedClients.find {
            it.getClient()?.clientUuid == clientUuid
        }
    }

    private fun getAllClientConnections(username: String): List<ClientConnection> {
        return services.connectedClients.filter {
            it.getClient()?.minecraftUsername?.equals(username, ignoreCase = true) == true
        }
    }

    private fun generateRandomLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }
}
