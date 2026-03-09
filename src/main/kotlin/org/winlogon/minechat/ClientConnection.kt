package org.winlogon.minechat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.luben.zstd.Zstd

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

import org.bukkit.Bukkit
import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.Client

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClientConnection(
    private val socket: Socket,
    private val plugin: MineChatServerPlugin,
    private val miniMessage: MiniMessage
) : Runnable {
    private val logger = plugin.logger
    object ChatGradients {
        val JOIN = Pair("#27AE60", "#2ECC71")
        val LEAVE = Pair("#C0392B", "#E74C3C")
        val AUTH = Pair("#8E44AD", "#9B59B6")
        val INFO = Pair("#2980B9", "#3498DB")
    }

    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    private val cborMapper = ObjectMapper(CBORFactory()).registerModule(KotlinModule.Builder().build())
    private val reader = DataInputStream(socket.getInputStream())
    private val writer = DataOutputStream(socket.getOutputStream())
    private var client: Client? = null
    private var running = true
    
    // Keep-alive timeout tracking
    private var lastPacketTime = System.currentTimeMillis()

    /** 15 seconds as per spec */
    private val keepAliveTimeout = 15000L

    /** Send PING every 10 seconds */
    private val pingInterval = 10000L
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val disconnected = AtomicBoolean(false)

    fun getClient(): Client? = client

    private fun broadcastMinecraft(colors: Pair<String, String>?, message: String) {
        val formattedMessage = colors?.let { "<gradient:${it.first}:${it.second}>$message</gradient>" } ?: message
        val finalMessage = miniMessage.deserialize(formattedMessage)
        Bukkit.broadcast(formatPrefixed(finalMessage))
    }

    override fun run() {
        try {
            // Schedule periodic PING packets for keep-alive
            scheduledExecutor.scheduleAtFixedRate({
                if (running && !disconnected.get()) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastPacketTime > pingInterval) {
                        try {
                            sendMessage(PacketTypes.PING, PingPayload(System.currentTimeMillis()))
                            logger.fine("Sent PING for keep-alive")
                        } catch (e: Exception) {
                            logger.warning("Failed to send PING: ${e.message}")
                            disconnected.set(true)
                        }
                    }
                }
            }, pingInterval, pingInterval, TimeUnit.MILLISECONDS)
            
            while (running) {
                // Check for keep-alive timeout before reading next packet
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastPacketTime > keepAliveTimeout) {
                    logger.info("Client connection timed out after ${keepAliveTimeout}ms of inactivity")
                    break
                }
                
                // Set socket timeout to avoid blocking indefinitely
                socket.soTimeout = 1000 // 1 second timeout for reads
                
                try {
                    val decompressedLen = reader.readInt()
                    if (decompressedLen <= 0) {
                        logger.warning("Received non-positive decompressed length: $decompressedLen. Terminating connection.")
                        break // Terminate connection
                    }
                    logger.fine("Received decompressedLen: $decompressedLen")

                    val compressedLen = reader.readInt()
                    if (compressedLen <= 0) {
                        logger.warning("Received non-positive compressed length: $compressedLen. Terminating connection.")
                        break // Terminate connection
                    }
                    logger.fine("Received compressedLen: $compressedLen")
                    
                    // Update last packet time after successful read
                    lastPacketTime = currentTime

                    val compressed = ByteArray(compressedLen)
                    reader.readFully(compressed)

                    val decompressed = Zstd.decompress(compressed, decompressedLen)
                    if (decompressed.size != decompressedLen) {
                        logger.warning("Decompressed size mismatch. Expected $decompressedLen, got ${decompressed.size}. Terminating connection.")
                        break // Terminate connection
                    }

                    val mineChatPacket = cborMapper.readValue(decompressed, MineChatPacket::class.java)
                    logger.fine("Received MineChatPacket: $mineChatPacket")
                    
                    // Update last packet time for any received packet
                    lastPacketTime = System.currentTimeMillis()

                    when (mineChatPacket.packetType) {
                        PacketTypes.LINK -> {
                            val payload = cborMapper.convertValue(mineChatPacket.payload, LinkPayload::class.java)
                            logger.fine("Received LINK message: $payload")
                            handleAuth(payload)
                        }
                        PacketTypes.CAPABILITIES -> {
                            val payload = cborMapper.convertValue(mineChatPacket.payload, CapabilitiesPayload::class.java)
                            logger.fine("Received CAPABILITIES message: $payload")
                            handleCapabilities(payload)
                        }
                        PacketTypes.CHAT_MESSAGE -> {
                            val payload = cborMapper.convertValue(mineChatPacket.payload, ChatMessagePayload::class.java)
                            logger.fine("Received CHAT_MESSAGE message: $payload")
                            handleChat(payload)
                        }
                        PacketTypes.PING -> {
                            val payload = cborMapper.convertValue(mineChatPacket.payload, PingPayload::class.java)
                            logger.fine("Received PING message: $payload")
                            handlePing(payload)
                        }
                        PacketTypes.PONG -> {
                            val payload = cborMapper.convertValue(mineChatPacket.payload, PongPayload::class.java)
                            logger.fine("Received PONG message: $payload")
                            handlePong(payload)
                        }
                        else -> plugin.loggerProvider.logger.warning("Unknown packet type: ${mineChatPacket.packetType}")
                    }
                } catch (_: SocketTimeoutException) {
                    // This is expected due to keep-alive timeout checking, continue loop
                    continue
                } catch (e: Exception) {
                    plugin.loggerProvider.logger.warning("Client error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            plugin.loggerProvider.logger.warning("Client error: ${e.message}")
        } finally {
            client?.let {
                broadcastMinecraft(ChatGradients.LEAVE, "${it.minecraftUsername} has left the chat.")
            }
            close()
            plugin.removeClient(this)
        }
    }

    private fun sendBannedMessage(ban: Ban) {
        logger.fine("Sending banned message to client: $ban")
        disconnect(ban.reason ?: "You are banned from MineChat.")
    }

    fun disconnect(reason: String) {
        sendMessage(PacketTypes.DISCONNECT, DisconnectPayload(reason))
        close()
    }

    private fun handleAuth(payload: LinkPayload) {
        logger.fine("Handling auth with payload: $payload")

        val banStorage = plugin.banStorage
        val clientUuid = payload.clientUuid
        val linkCode = payload.linkingCode

        // Check ban by client UUID first
        banStorage.getBan(clientUuid, null)?.let {
            sendBannedMessage(it)
            return
        }

        if (linkCode.isNotEmpty()) {
            handleLinkAuth(clientUuid, linkCode)
            return
        }

        handleExistingClientAuth(clientUuid)
    }

    private fun handleLinkAuth(clientUuid: String, linkCode: String) {
        val link = plugin.linkCodeStorage.find(linkCode)
            ?: return disconnect("Invalid or expired link code")

        // Check ban by Minecraft username
        plugin.banStorage.getBan(null, link.minecraftUsername)?.let {
            sendBannedMessage(it)
            return
        }

        if (link.expiresAt <= System.currentTimeMillis()) {
            disconnect("Invalid or expired link code")
            return
        }

        val client = Client(
            clientUuid = clientUuid,
            minecraftUuid = link.minecraftUuid,
            minecraftUsername = link.minecraftUsername
        )

        plugin.clientStorage.add(client)
        plugin.linkCodeStorage.remove(link.code)
        this.client = client

        sendMessage(
            PacketTypes.LINK_OK,
            LinkOkPayload(minecraftUuid = link.minecraftUuid.toString())
        )
        sendMessage(PacketTypes.AUTH_OK, AuthOkPayload())

        broadcastMinecraft(ChatGradients.AUTH, "${link.minecraftUsername} has successfully authenticated.")
    }

    private fun handleExistingClientAuth(clientUuid: String) {
        val client = plugin.clientStorage.find(clientUuid, null)
            ?: return disconnect("Client not registered")

        plugin.banStorage.getBan(null, client.minecraftUsername)?.let {
            sendBannedMessage(it)
            return
        }

        this.client = client
        sendMessage(PacketTypes.AUTH_OK, AuthOkPayload())

        broadcastMinecraft(ChatGradients.JOIN, "${client.minecraftUsername} has joined the chat.")
    }

    private fun handleChat(payload: ChatMessagePayload) {
        client?.let {
            // Check payload.format here if needed. Assuming "commonmark" for now.
            val message = miniMessage.deserialize(payload.content) // Deserialize the content string to an Adventure Component
            val usernamePlaceholder = Component.text(it.minecraftUsername, NamedTextColor.DARK_GREEN)
            val formattedMsg = miniMessage.deserialize(
                "<gray><sender><dark_gray>:</dark_gray> <message></gray>",
                Placeholder.component("sender", usernamePlaceholder),
                Placeholder.component("message", message)
            )
            val finalMsg = formatPrefixed(formattedMsg)
            Bukkit.broadcast(finalMsg)
            val chatMessagePayload = ChatMessagePayload(
                format = "commonmark",
                content = miniMessage.serialize(message)
            )
            plugin.broadcastToClients(PacketTypes.CHAT_MESSAGE, chatMessagePayload)
        }
    }

    private fun handleCapabilities(payload: CapabilitiesPayload) {
        client?.let {
            it.supportsComponents = payload.supportsComponents
            plugin.clientStorage.add(it) // Update the client in storage
            logger.fine("Client ${it.minecraftUsername} updated with capabilities: supportsComponents=${it.supportsComponents}")
        } ?: run {
            logger.warning("Received CAPABILITIES packet before client was authenticated. Disconnecting.")
            disconnect("Received CAPABILITIES before authentication.")
        }
    }

    private fun handlePing(payload: PingPayload) {
        // Respond with a PONG packet, echoing the timestamp
        sendMessage(PacketTypes.PONG, PongPayload(payload.timestampMs))
        logger.fine("Responded to PING from client with timestamp ${payload.timestampMs}")
    }

    private fun handlePong(payload: PongPayload) {
        // For now, just log that a PONG was received.
        // TODO: use this for RTT calculation
        logger.fine("Received PONG from client with timestamp ${payload.timestampMs}")
    }

    @Suppress("UNCHECKED_CAST")
    fun sendMessage(packetType: Int, payload: Any) {
        logger.fine("Sending packet type $packetType with payload: $payload")
        try {
            // Convert the payload object to a Map<Int, Any?>
            val payloadMap = cborMapper.convertValue(payload, Map::class.java) as Map<Int, Any?>
            val mineChatPacket = MineChatPacket(packetType, payloadMap)

            val serialized = cborMapper.writeValueAsBytes(mineChatPacket)
            val compressed = Zstd.compress(serialized)
            
            // Validate sizes are positive (per spec requirement)
            if (serialized.size > Int.MAX_VALUE || compressed.size > Int.MAX_VALUE) {
                throw IllegalArgumentException("Packet too large")
            }
            
            // DataOutputStream already uses big-endian signed integers by default
            writer.writeInt(serialized.size)
            writer.writeInt(compressed.size)
            writer.write(compressed)
            writer.flush()
        } catch (e: Exception) {
            plugin.loggerProvider.logger.warning("Error sending message: ${e.message}")
        }
    }

    fun close() {
        running = false
        disconnected.set(true)
        scheduledExecutor.shutdown()
        socket.close()
    }

    fun formatPrefixed(message: Component): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(message)
    }
}
