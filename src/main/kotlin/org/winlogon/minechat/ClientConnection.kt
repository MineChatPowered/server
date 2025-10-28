package org.winlogon.minechat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.luben.zstd.Zstd

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

import org.bukkit.Bukkit

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class ClientConnection(
    private val socket: Socket,
    private val plugin: MineChatServerPlugin,
    private val miniMessage: MiniMessage
) : Runnable {
    object ChatGradients {
        val JOIN = Pair("#27AE60", "#2ECC71")
        val LEAVE = Pair("#C0392B", "#E74C3C")
        val AUTH = Pair("#8E44AD", "#9B59B6")
        val INFO = Pair("#2980B9", "#3498DB")
    }

    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component =
            LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    private val cborMapper = ObjectMapper(CBORFactory()).registerModule(KotlinModule.Builder().build())
    private val reader = DataInputStream(socket.getInputStream())
    private val writer = DataOutputStream(socket.getOutputStream())
    private var client: Client? = null
    private var running = true

    fun getClient(): Client? = client

    private fun broadcastMinecraft(colors: Pair<String, String>?, message: String) {
        val formattedMessage = colors?.let { "<gradient:${it.first}:${it.second}>$message</gradient>" } ?: message
        val finalMessage = miniMessage.deserialize(formattedMessage)
        Bukkit.broadcast(formatPrefixed(finalMessage))
    }

    override fun run() {
        try {
            while (running) {
                val decompressedLen = reader.readInt()
                if (decompressedLen <= 0) continue
                val compressedLen = reader.readInt()
                if (compressedLen <= 0) continue

                val compressed = ByteArray(compressedLen)
                reader.readFully(compressed)

                val decompressed = Zstd.decompress(compressed, decompressedLen)
                val json = cborMapper.readValue(decompressed, Map::class.java) as Map<String, Any>

                when (json["type"] as String) {
                    "AUTH" -> handleAuth(json["payload"] as Map<String, Any>)
                    "CHAT" -> handleChat(json["payload"] as Map<String, Any>)
                    "DISCONNECT" -> break
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Client error: ${e.message}")
        } finally {
            client?.let {
                broadcastMinecraft(ChatGradients.LEAVE, "${it.minecraftUsername} has left the chat.")
                plugin.broadcastToClients(
                    mapOf(
                        "type" to "SYSTEM",
                        "payload" to mapOf(
                            "event" to "leave",
                            "username" to it.minecraftUsername,
                            "message" to "${it.minecraftUsername} has left the chat."
                        )
                    )
                )
            }
            close()
            plugin.removeClient(this)
        }
    }

    private fun sendBannedMessage(ban: Ban) {
        disconnect(ban.reason ?: "You are banned from MineChat.")
    }

    fun disconnect(reason: String) {
        sendMessage(
            mapOf(
                "type" to "DISCONNECT",
                "payload" to mapOf("reason" to reason)
            )
        )
        close()
    }

    private fun handleAuth(payload: Map<String, Any>) {
        val banStorage = plugin.getBanStorage()
        val clientUuid = payload["client_uuid"] as String
        val linkCode = payload["link_code"] as String

        var ban = banStorage.getBan(clientUuid, null)
        if (ban != null) {
            sendBannedMessage(ban)
            return
        }

        if (linkCode.isNotEmpty()) {
            val link = plugin.getLinkCodeStorage().find(linkCode)
            if (link != null) {
                ban = banStorage.getBan(null, link.minecraftUsername)
                if (ban != null) {
                    sendBannedMessage(ban)
                    return
                }
                if (link.expiresAt > System.currentTimeMillis()) {
                    val client = Client(clientUuid = clientUuid, minecraftUuid = link.minecraftUuid, minecraftUsername = link.minecraftUsername)
                    plugin.getClientStorage().add(client)
                    plugin.getLinkCodeStorage().remove(link.code)
                    this.client = client
                    sendMessage(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "success",
                                "message" to "Linked to ${link.minecraftUsername}",
                                "minecraft_uuid" to link.minecraftUuid.toString(),
                                "username" to link.minecraftUsername
                            )
                        )
                    )
                    broadcastMinecraft(
                        ChatGradients.AUTH,
                        "${link.minecraftUsername} has successfully authenticated."
                    )
                    plugin.broadcastToClients(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "join",
                                "username" to link.minecraftUsername,
                                "message" to "${link.minecraftUsername} has joined the chat."
                            )
                        )
                    )
                } else {
                    sendMessage(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "failure",
                                "message" to "Invalid or expired link code"
                            )
                        )
                    )
                }
            } else {
                sendMessage(
                    mapOf(
                        "type" to "AUTH_ACK",
                        "payload" to mapOf(
                            "status" to "failure",
                            "message" to "Invalid or expired link code"
                        )
                    )
                )
            }
        } else {
            val client = plugin.getClientStorage().find(clientUuid, null)
            if (client != null) {
                ban = banStorage.getBan(null, client.minecraftUsername)
                if (ban != null) {
                    sendBannedMessage(ban)
                    return
                }
                this.client = client
                sendMessage(
                    mapOf(
                        "type" to "AUTH_ACK",
                        "payload" to mapOf(
                            "status" to "success",
                            "message" to "Welcome back, ${client.minecraftUsername}",
                            "minecraft_uuid" to client.minecraftUuid.toString(),
                            "username" to client.minecraftUsername
                        )
                    )
                )
                broadcastMinecraft(ChatGradients.JOIN, "${client.minecraftUsername} has joined the chat.")
                plugin.broadcastToClients(
                    mapOf(
                        "type" to "SYSTEM",
                        "payload" to mapOf(
                            "event" to "join",
                            "username" to client.minecraftUsername,
                            "message" to "${client.minecraftUsername} has joined the chat."
                        )
                    )
                )
            } else {
                sendMessage(
                    mapOf(
                        "type" to "AUTH_ACK",
                        "payload" to mapOf(
                            "status" to "failure",
                            "message" to "Client not registered"
                        )
                    )
                )
            }
        }
    }

    private fun handleChat(payload: Map<String, Any>) {
        client?.let {
            val messageJson = cborMapper.writeValueAsString(payload["message"])
            val message = GsonComponentSerializer.gson().deserialize(messageJson)

            val usernamePlaceholder = Component.text(it.minecraftUsername, NamedTextColor.DARK_GREEN)
            val formattedMsg = miniMessage.deserialize(
                "<gray><sender><dark_gray>:</dark_gray> <message></gray>",
                Placeholder.component("sender", usernamePlaceholder),
                Placeholder.component("message", message)
            )
            val finalMsg = formatPrefixed(formattedMsg)
            Bukkit.broadcast(finalMsg)
            plugin.broadcastToClients(
                mapOf(
                    "type" to "BROADCAST",
                    "payload" to mapOf(
                        "from" to "[MineChat] ${it.minecraftUsername}",
                        "message" to payload["message"]!!
                    )
                )
            )
        }
    }

    fun sendMessage(message: Map<String, Any>) {
        try {
            val serialized = cborMapper.writeValueAsBytes(message)
            val compressed = Zstd.compress(serialized)
            writer.writeInt(serialized.size)
            writer.writeInt(compressed.size)
            writer.write(compressed)
            writer.flush()
        } catch (e: Exception) {
            plugin.logger.warning("Error sending message: ${e.message}")
        }
    }

    fun close() {
        running = false
        socket.close()
    }

    fun formatPrefixed(message: Component): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(message)
    }
}
