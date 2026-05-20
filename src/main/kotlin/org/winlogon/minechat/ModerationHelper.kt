package org.winlogon.minechat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

import org.winlogon.minechat.storage.ClientStorage

data class ClientResolution(
    val storedClients: List<ClientStorage.CachedClient>,
    val connectedClients: List<ClientConnection>
)

data class ActionPrep(
    val scope: Int,
    val resolution: ClientResolution,
    val message: String
)

object ModerationHelper {
    fun resolveClients(
        services: PluginServices,
        player: String,
        scope: Int
    ): ClientResolution {
        val storedClients = services.clientStorage.findByUsername(player)
        val connectedClients = if (scope == ModerationScope.CLIENT) {
            if (storedClients.isNotEmpty()) {
                val singleClient = storedClients.first()
                listOfNotNull(services.connectedClients.find {
                    it.getClient()?.clientUuid == singleClient.clientUuid
                })
            } else {
                emptyList()
            }
        } else {
            services.connectedClients.filter {
                it.getClient()?.minecraftUsername?.equals(player, ignoreCase = true) == true
            }
        }
        return ClientResolution(storedClients, connectedClients)
    }

    fun prepareAction(
        services: PluginServices,
        player: String,
        affectAccount: Boolean,
        reason: String?,
        defaultMessage: String
    ): ActionPrep? {
        val scope = if (affectAccount) ModerationScope.ACCOUNT else ModerationScope.CLIENT
        val resolution = resolveClients(services, player, scope)
        if (scope == ModerationScope.CLIENT && resolution.storedClients.isEmpty()) return null
        return ActionPrep(
            scope,
            resolution,
            reason?.takeIf { it.isNotBlank() } ?: defaultMessage
        )
    }

    fun sendToClients(
        clients: List<ClientConnection>,
        action: Int,
        scope: Int,
        message: String,
        durationSeconds: Int? = null,
        disconnect: Boolean = false
    ) {
        clients.forEach { client ->
            if (disconnect) {
                client.sendModerationAndDisconnect(action, scope, message, durationSeconds)
            } else {
                client.sendModeration(action, scope, message, durationSeconds)
            }
        }
    }

    fun scopeText(scope: Int): String {
        return if (scope == ModerationScope.ACCOUNT) "(all clients)" else ""
    }

    fun notConnectedMessage(action: String): Component {
        val message = when (action) {
            "warn" -> "Warning cannot be delivered."
            "mute" -> "Mute will be applied when they connect."
            "ban" -> "Ban applies to their account."
            else -> "Player is not connected."
        }
        return Component.text("Note: $message", NamedTextColor.YELLOW)
    }
}
