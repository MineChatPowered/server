@file:OptIn(ExperimentalSerializationApi::class)

package org.winlogon.minechat

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

import org.bukkit.Bukkit
import org.winlogon.minechat.storage.ClientStorage.CachedClient
import org.winlogon.minechat.storage.BanStorage.BanInfo

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Handles a client connection to the MineChat server.
 *
 * This class manages the connection lifecycle including:
 * - Reading and writing packets using CBOR + zstd compression
 * - Handling LINK/LINK_OK authentication flow
 * - Processing chat messages and moderation actions
 * - Sending periodic PING messages for keep-alive
 *
 * @param socket The client socket connection
 * @param plugin The MineChat plugin instance
 */
class ClientConnection(
    private val socket: Socket,
    private val plugin: MineChatPlugin
) : Runnable {
    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
        val CBOR = createCbor()
    }

    val reader = DataInputStream(socket.inputStream)
    val writer = DataOutputStream(socket.outputStream)

    private var client: CachedClient? = null
    private val running = AtomicBoolean(true)


    /** Tracks if LINK_OK has been sent */
    private var linkAuthCompleted = false
    /** Tracks if CAPABILITIES has been received */
    private var capabilitiesReceived = false
    /** Supported formats from client's CAPABILITIES packet */
    private var supportedFormats: List<String> = emptyList()
    /** Preferred format from client's CAPABILITIES packet */
    private var preferredFormat: String? = null

    // Keep-alive timeout tracking
    private var lastPacketTime = System.currentTimeMillis()
    private val keepAliveTimeout = 15000L // 15 seconds
    private var currentRtt: Long = 0

    private val disconnected = AtomicBoolean(false)
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic PING messages every 10 seconds
        scheduledExecutor.scheduleAtFixedRate({
            if (running.get() && !disconnected.get()) {
                sendPing()
            }
        }, 10, 10, TimeUnit.SECONDS)
    }

    /**
     * Main connection loop that processes incoming packets.
     *
     * This method runs continuously until the client disconnects
     * or an error occurs, reading and processing MineChat protocol packets.
     */
    override fun run() {
        try {
            logger.fine("ClientConnection.run() started for ${socket.remoteSocketAddress}")

            while (running.get() && !disconnected.get()) {
                // Keep-alive timeout
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastPacketTime > keepAliveTimeout) {
                    logger.warning("Client connection timed out after ${keepAliveTimeout}ms of inactivity")
                    break // Terminate connection
                }

                // Read the framing layer
                val decompressedLen = reader.readInt()
                val compressedLen = reader.readInt()
                logger.fine("Read frame header: decompressedLen=$decompressedLen, compressedLen=$compressedLen")

                if (decompressedLen <= 0 || compressedLen <= 0) {
                    logger.warning("Received non-positive frame size. Terminating connection.")
                    break
                }

                // Update last packet time after successful read
                lastPacketTime = currentTime

                val compressed = ByteArray(compressedLen)
                reader.readFully(compressed)
                logger.fine("Read compressed data: ${compressed.size} bytes")

                // Log compressed bytes hex for debugging
                val compressedHex = compressed.take(32).joinToString("") { "%02X".format(it) }
                logger.fine("Compressed hex (first 32 bytes): $compressedHex")

                try {
                    logger.fine("Decompressing ${compressed.size} bytes to expected $decompressedLen bytes...")
                    val decompressed: ByteArray
                    try {
                        decompressed = Zstd.decompress(compressed, decompressedLen)
                    } catch (t: Throwable) {
                        logger.log(Level.SEVERE, "Decompression failed: ${t.javaClass.name}: ${t.message}", t)
                        break
                    }
                    logger.fine("Decompression complete: ${decompressed.size} bytes")

                    if (decompressed.size != decompressedLen) {
                        logger.warning("Decompressed size mismatch. Expected $decompressedLen, got ${decompressed.size}. Terminating connection.")
                        break
                    }

                    val hexPreview = decompressed.take(32).joinToString("") { "%02X".format(it) }
                    logger.fine("Decompressed CBOR: len=${decompressed.size}, hex=$hexPreview")

                    val mineChatPacket = try {
                        CBOR.decodeFromByteArray(MineChatPacket, decompressed)
                    } catch (e: Exception) {
                        logger.log(Level.SEVERE, "CBOR deserialization failed: ${e.message}", e)
                        e.printStackTrace()
                        break
                    }

                    logger.fine("Decoded packet: type=${mineChatPacket.packetType}, payloadClass=${mineChatPacket.payload::class.java.simpleName}")

                    // Update last packet time for any received packet
                    lastPacketTime = System.currentTimeMillis()

                    handlePacketType(mineChatPacket)
                } catch (e: Exception) {
                    logger.severe("Error in packet processing: ${e.message}")
                    e.printStackTrace()
                    break
                }
            }
        } catch (_: SocketTimeoutException) {
            // This is expected due to keep-alive timeout checking, continue loop
        } catch (_: EOFException) {
            // Client disconnected normally - this is expected behavior
            logger.info("Client disconnected")
            return
        } catch (e: Exception) {
            if (running.get() && !disconnected.get()) {
                logger.severe("Client error in run loop: ${e.message}")
                e.printStackTrace()
            }
        } finally {
            client?.let {
                broadcastMinecraft(ChatGradients.LEAVE, "${it.minecraftUsername} has left the chat.")
            }
            close()
            plugin.removeClient(this)
        }
    }

    fun handlePacketType(mineChatPacket: MineChatPacket) {
        when (mineChatPacket.packetType) {
            PacketTypes.LINK -> {
                val payload = mineChatPacket.payload as LinkPayload
                logger.fine("Received LINK message: $payload")
                handleAuth(payload)
            }

            PacketTypes.CAPABILITIES -> {
                val payload = mineChatPacket.payload as CapabilitiesPayload
                logger.fine("Received CAPABILITIES message: $payload")
                handleCapabilities(payload)
            }

            PacketTypes.CHAT_MESSAGE -> {
                val payload = mineChatPacket.payload as ChatMessagePayload
                logger.fine("Received CHAT_MESSAGE message: $payload")
                handleChat(payload)
            }

            PacketTypes.PING -> {
                val payload = mineChatPacket.payload as PingPayload
                logger.fine("Received PING message: $payload")
                handlePing(payload)
            }

            PacketTypes.PONG -> {
                val payload = mineChatPacket.payload as PongPayload
                logger.fine("Received PONG message: $payload")
                handlePong(payload)
            }

            else -> logger.warning("Unknown packet type: ${mineChatPacket.packetType}")
        }
    }

    private fun sendPing() {
        val timestamp = System.currentTimeMillis()
        sendMessage(PacketTypes.PING, PingPayload(timestamp_ms = timestamp))
    }

    private fun sendMessage(packetType: Int, payload: LinkOkPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending LinkOkPayload: ${e.message}")
        }
    }

    private fun sendMessage(packetType: Int, payload: AuthOkPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error sending AuthOkPayload: ${e.message}", e)
        }
    }

    private fun sendMessage(packetType: Int, payload: PingPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error sending PingPayload: ${e.message}", e)
        }
    }

    private fun sendMessage(packetType: Int, payload: PongPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error sending PongPayload: ${e.message}", e)
        }
    }

    private fun <T : PacketPayload> createPacket(packetType: Int, payload: T): MineChatPacket {
        return MineChatPacket(packetType, payload)
    }

    private fun sendPacket(mineChatPacket: MineChatPacket) {
        try {
            logger.info("sendPacket: serializing packetType=${mineChatPacket.packetType}")
            val serialized = CBOR.encodeToByteArray(MineChatPacket, mineChatPacket)
            logger.info("sendPacket: serialized ${serialized.size} bytes")

            val compressed = Zstd.compress(serialized)
            logger.info("sendPacket: compressed to ${compressed.size} bytes")

            if (serialized.size > Int.MAX_VALUE || compressed.size > Int.MAX_VALUE) {
                throw IllegalArgumentException("Packet too large")
            }

            writer.writeInt(serialized.size)
            writer.writeInt(compressed.size)
            writer.write(compressed)
            writer.flush()
            logger.info("sendPacket: sent packetType=${mineChatPacket.packetType}")
        } catch (e: Exception) {
            logger.severe("sendPacket failed: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun close() {
        running.set(false)
        disconnected.set(true)
        scheduledExecutor.shutdown()
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    fun formatPrefixed(message: Component, username: String? = null): Component {
        val c = MINECHAT_PREFIX_COMPONENT.append(Component.space())
        return if (username != null) {
            c.append(Component.text("$username: ")).append(message)
        } else {
            c.append(message)
        }
    }

    private fun handleAuth(payload: LinkPayload) {
        logger.info("Handling auth with payload: $payload")

        val banStorage = plugin.banStorage
        val clientUuid = payload.client_uuid
        val linkCode = payload.linking_code

        // Check ban by client UUID first
        banStorage.getBan(clientUuid, null)?.let {
            sendBannedMessage(it)
            return
        }

        // Handle reconnection (empty link code) - look up existing client
        if (linkCode.isEmpty()) {
            val existingClient = plugin.clientStorage.find(clientUuid, null)
            if (existingClient != null) {
                this.client = existingClient
                sendMessage(PacketTypes.LINK_OK, LinkOkPayload(minecraft_uuid = existingClient.minecraftUuid.toString()))
                linkAuthCompleted = true
                logger.info("Reconnected client: ${existingClient.minecraftUsername}")
                return
            } else {
                disconnect()
                return
            }
        }

        // Look up the link code
        val link = plugin.linkCodeStorage.find(linkCode)
        if (link == null) {
            logger.warning("Invalid link code: $linkCode")
            disconnect()
            return
        }

        // Check if link code is expired (15 minutes)
        if (System.currentTimeMillis() > link.expiresAt) {
            logger.warning("Expired link code: $linkCode")
            plugin.linkCodeStorage.remove(linkCode)
            disconnect()
            return
        }

        // Check ban by Minecraft UUID
        banStorage.getBan(null, link.minecraftUuid.toString())?.let {
            sendBannedMessage(it)
            return
        }

        // Delete the used link code
        plugin.linkCodeStorage.remove(linkCode)

        // Create or update client
        val existingClient = plugin.clientStorage.find(clientUuid, null)
        val newClient = if (existingClient != null) {
            CachedClient(
                clientUuid = clientUuid,
                minecraftUuid = link.minecraftUuid,
                minecraftUsername = link.minecraftUsername,
                supportsComponents = existingClient.supportsComponents
            )
        } else {
            CachedClient(
                clientUuid = clientUuid,
                minecraftUuid = link.minecraftUuid,
                minecraftUsername = link.minecraftUsername,
                supportsComponents = false
            )
        }

        this.client = newClient

        // Per spec: send LINK_OK, wait for CAPABILITIES, then send AUTH_OK
        sendMessage(PacketTypes.LINK_OK, LinkOkPayload(minecraft_uuid = link.minecraftUuid.toString()))
        linkAuthCompleted = true

        // Now wait for CAPABILITIES before completing auth
        logger.info("Sent LINK_OK, waiting for CAPABILITIES...")
    }

    private fun handleCapabilities(payload: CapabilitiesPayload) {
        if (!linkAuthCompleted) {
            logger.warning("Received CAPABILITIES without prior LINK_OK. Ignoring.")
            return
        }

        if (capabilitiesReceived) {
            logger.warning("Received duplicate CAPABILITIES packet. Ignoring.")
            return
        }

        supportedFormats = payload.supported_formats
        preferredFormat = payload.preferred_format

        val supportsComponents = supportedFormats.contains("components")

        client?.let { client ->
            plugin.clientStorage.add(
                client.clientUuid,
                client.minecraftUuid,
                client.minecraftUsername,
                supportsComponents
            )

            sendMessage(PacketTypes.AUTH_OK, AuthOkPayload())
            capabilitiesReceived = true

            if (supportsComponents) {
                broadcastMinecraft(ChatGradients.JOIN, "${client.minecraftUsername} has joined the chat.")
            } else {
                broadcastMinecraft(ChatGradients.AUTH, "${client.minecraftUsername} has successfully authenticated.")
            }

            logger.info("Client ${client.minecraftUsername} authenticated with capabilities: supportedFormats=${supportedFormats}, preferredFormat=${preferredFormat}")
        } ?: run {
            logger.warning("Received CAPABILITIES packet but client is null. This should not happen.")
            disconnect()
        }
    }

    private fun handleChat(payload: ChatMessagePayload) {
        if (this.client == null) return
        if (!capabilitiesReceived) {
            logger.warning("Received CHAT_MESSAGE before CAPABILITIES. Ignoring.")
            return
        }

        // Validate format against supported_formats per spec Section 8.5
        val format = payload.format
        if (format !in supportedFormats) {
            logger.warning("Client sent message in unsupported format '$format'. Supported: $supportedFormats. Ignoring.")
            return
        }

        // Get the username for prefixing
        val username = this.client?.minecraftUsername ?: return

        // Process the chat message
        val content = payload.content

        if (format == "commonmark") {
            // Parse commonmark and send to Minecraft
            val component = try {
                plugin.miniMessage.deserialize(content)
            } catch (e: Exception) {
                logger.warning("Failed to parse MiniMessage: ${e.message}")
                Component.text(content)
            }

            broadcastMinecraft(formatPrefixed(component, username))
        } else if (format == "components") {
            // Parse JSON component format and send to Minecraft
            val component = try {
                GsonComponentSerializer.gson().deserialize(content)
            } catch (e: Exception) {
                logger.warning("Failed to parse JSON component: ${e.message}")
                Component.text(content)
            }

            broadcastMinecraft(formatPrefixed(component, username))
        }
    }

    private fun handlePing(payload: PingPayload) {
        sendMessage(PacketTypes.PONG, PongPayload(payload.timestamp_ms))
        logger.info("Responded to PING from client with timestamp ${payload.timestamp_ms}")
    }

    private fun handlePong(payload: PongPayload) {
        val now = System.currentTimeMillis()
        currentRtt = now - payload.timestamp_ms
        logger.info("Received PONG from client with RTT: ${currentRtt}ms")
    }

    private fun broadcastMinecraft(gradient: Pair<String, String>, message: String) {
        val gradientColor = "<gradient:${gradient.first}:${gradient.second}>$message"
        val component = plugin.miniMessage.deserialize(gradientColor)
        broadcastMinecraft(formatPrefixed(component))
    }

    private fun broadcastMinecraft(component: Component) {
        Bukkit.getServer().sendMessage(component)
    }

    private fun sendBannedMessage(ban: BanInfo) {
        // Send MODERATION packet with ban action, then close socket per spec
        val moderationPayload = ModerationPayload(
            action = ModerationAction.BAN,
            scope = ModerationScope.ACCOUNT,
            reason = ban.reason,
            duration_seconds = null
        )
        try {
            val packet = MineChatPacket(PacketTypes.MODERATION, moderationPayload)
            sendPacket(packet)
        } catch (e: Exception) {
            logger.warning("Failed to send MODERATION packet: ${e.message}")
        }
        close()
    }

    fun getClient(): CachedClient? = client

    /**
     * Sends a MODERATION packet to the client, then closes the connection.
     * Used for kick/ban actions per the protocol spec.
     */
    fun sendModeration(action: Int, scope: Int, reason: String?, durationSeconds: Int?) {
        val payload = ModerationPayload(action, scope, reason, durationSeconds)
        try {
            val packet = MineChatPacket(PacketTypes.MODERATION, payload)
            sendPacket(packet)
        } catch (e: Exception) {
            logger.warning("Failed to send MODERATION packet: ${e.message}")
        }
    }

    fun sendModerationAndDisconnect(action: Int, scope: Int, reason: String?, durationSeconds: Int?) {
        val payload = ModerationPayload(action, scope, reason, durationSeconds)
        try {
            val packet = MineChatPacket(PacketTypes.MODERATION, payload)
            sendPacket(packet)
        } catch (e: Exception) {
            logger.warning("Failed to send MODERATION packet: ${e.message}")
        }
        close()
    }

    /**
     * Sends a CHAT_MESSAGE to this client, respecting the client's capabilities.
     * Per spec: Must send in a format declared in supported_formats, preferring preferred_format.
     * Note: The sourceComponent should already include the username prefix.
     */
    fun sendChatMessage(sourceFormat: String, sourceContent: String, sourceComponent: Component) {
        if (!capabilitiesReceived) return

        val targetFormat = selectFormatForClient(sourceFormat)
        if (targetFormat == null) {
            logger.fine("Cannot send message to ${client?.minecraftUsername}: no compatible format")
            return
        }

        val content = if (targetFormat == "components") {
            GsonComponentSerializer.gson().serialize(sourceComponent)
        } else {
            sourceContent
        }

        val payload = ChatMessagePayload(targetFormat, content)
        try {
            val packet = MineChatPacket(PacketTypes.CHAT_MESSAGE, payload)
            sendPacket(packet)
        } catch (e: Exception) {
            logger.warning("Failed to send CHAT_MESSAGE to client: ${e.message}")
        }
    }

    /**
     * Selects the best format to send to this client.
     * Per spec: MUST use a format in supported_formats, SHOULD use preferred_format.
     */
    private fun selectFormatForClient(sourceFormat: String): String? {
        if (sourceFormat in supportedFormats) {
            val pref = preferredFormat
            if (pref != null && pref in supportedFormats && pref != sourceFormat) {
                return pref
            }
            return sourceFormat
        }

        // Source format not supported - try preferred format
        val pref = preferredFormat
        if (pref != null && pref in supportedFormats) {
            return pref
        }

        // Fall back to first supported format
        return supportedFormats.firstOrNull()
    }

    /**
     * Sends a SYSTEM_DISCONNECT packet, then closes the connection.
     * Used for server-initiated shutdown/maintenance.
     */
    fun sendSystemDisconnect(reasonCode: Int, message: String) {
        val payload = SystemDisconnectPayload(reasonCode, message)
        try {
            val packet = MineChatPacket(PacketTypes.SYSTEM_DISCONNECT, payload)
            sendPacket(packet)
        } catch (e: Exception) {
            logger.warning("Failed to send SYSTEM_DISCONNECT packet: ${e.message}")
        }
        close()
    }

    /**
     * Closes the connection without sending any packet (EOF).
     * Used for client-initiated disconnections.
     */
    fun disconnect() {
        close()
    }

    private val logger: Logger
        get() = plugin.logger
}
