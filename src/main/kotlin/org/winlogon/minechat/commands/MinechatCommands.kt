package org.winlogon.minechat.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

import org.winlogon.minechat.ChatGradients
import org.winlogon.minechat.ModerationAction
import org.winlogon.minechat.ModerationHelper
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

        val prep = ModerationHelper.prepareAction(services, player, affectAccount, reason, config.moderationDefaults.banMessage)
        if (prep == null) {
            actor.reply { Component.text("Player not found.", NamedTextColor.RED) }
            return
        }

        val expiresAt = if (durationMinutes == -1) null else System.currentTimeMillis() + (durationMinutes.toLong() * 60 * 1000)

        if (prep.scope == ModerationScope.CLIENT) {
            val singleClient = prep.resolution.storedClients.first()
            services.banStorage.add(
                clientUuid = singleClient.clientUuid,
                minecraftUuid = singleClient.minecraftUuid,
                minecraftUsername = player,
                reason = prep.message,
                expiresAt = expiresAt
            )
            ModerationHelper.sendToClients(
                prep.resolution.connectedClients,
                ModerationAction.BAN,
                prep.scope,
                prep.message,
                null,
                disconnect = true
            )
        } else {
            services.banStorage.add(
                clientUuid = null,
                minecraftUuid = prep.resolution.storedClients.first().minecraftUuid,
                minecraftUsername = player,
                reason = prep.message,
                expiresAt = expiresAt
            )
            if (prep.resolution.connectedClients.isEmpty()) {
                actor.reply(ModerationHelper.notConnectedMessage("ban"))
            } else {
                ModerationHelper.sendToClients(
                    prep.resolution.connectedClients,
                    ModerationAction.BAN,
                    prep.scope,
                    prep.message,
                    null,
                    disconnect = true
                )
            }
        }

        val durationText = if (durationMinutes == -1) "permanently" else "for $durationMinutes minutes"
        actor.reply {
            miniMessage.deserialize(
                "Banned <player> from MineChat <duration> <scope>.",
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("duration", durationText),
                Placeholder.unparsed("scope", ModerationHelper.scopeText(prep.scope))
            )
        }
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

        val prep = ModerationHelper.prepareAction(services, player, affectAccount, reason, config.moderationDefaults.muteMessage)
        if (prep == null) {
            actor.reply { Component.text("Player not found.", NamedTextColor.RED) }
            return
        }

        val expiresAt = if (duration == -1) null else System.currentTimeMillis() + (duration.toLong() * 60 * 1000)
        val durationSeconds = if (duration == -1) null else duration * 60

        services.muteStorage.add(
            minecraftUsername = player,
            reason = prep.message,
            expiresAt = expiresAt
        )

        if (prep.resolution.connectedClients.isEmpty() && prep.scope == ModerationScope.CLIENT) {
            actor.reply(ModerationHelper.notConnectedMessage("mute"))
        } else {
            ModerationHelper.sendToClients(
                prep.resolution.connectedClients,
                ModerationAction.MUTE,
                prep.scope,
                prep.message,
                durationSeconds,
                disconnect = false
            )
        }

        val durationText = if (duration == -1) "permanently" else "for $duration minutes"
        actor.reply { Component.text("Muted $player $durationText${ModerationHelper.scopeText(prep.scope)} in MineChat.", NamedTextColor.GREEN) }
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
        val prep = ModerationHelper.prepareAction(services, player, affectAccount, reason, config.moderationDefaults.warnMessage)
        if (prep == null) {
            actor.reply { Component.text("Player not found.", NamedTextColor.RED) }
            return
        }

        if (prep.resolution.connectedClients.isEmpty()) {
            actor.reply(ModerationHelper.notConnectedMessage("warn"))
            return
        }

        ModerationHelper.sendToClients(
            prep.resolution.connectedClients,
            ModerationAction.WARN,
            prep.scope,
            prep.message,
            null,
            disconnect = false
        )

        actor.reply { Component.text("Warned $player in MineChat${ModerationHelper.scopeText(prep.scope)}.", NamedTextColor.GREEN) }
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
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT
        val resolution = ModerationHelper.resolveClients(services, player, scope)

        if (resolution.connectedClients.isEmpty()) {
            actor.reply { Component.text("Player not found or not connected via MineChat.", NamedTextColor.RED) }
            return
        }

        val message = reason?.takeIf { it.isNotBlank() } ?: config.moderationDefaults.kickMessage

        ModerationHelper.sendToClients(
            resolution.connectedClients,
            ModerationAction.KICK,
            scope,
            message,
            null,
            disconnect = true
        )

        actor.reply { Component.text("Kicked $player from MineChat${ModerationHelper.scopeText(scope)}.", NamedTextColor.GREEN) }
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

    private fun generateRandomLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }
}
