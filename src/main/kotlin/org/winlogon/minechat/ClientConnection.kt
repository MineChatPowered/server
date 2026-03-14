package org.winlogon.minechat

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

import org.bukkit.Bukkit
import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.Client

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    }

    @OptIn(ExperimentalSerializationApi::class)
    val cbor = createCbor()

    val reader = DataInputStream(socket.inputStream)
    val writer = DataOutputStream(socket.outputStream)

    private var client: Client? = null
    private var running = true


    /** Tracks if LINK_OK has been sent */
    private var linkAuthCompleted = false
    /** Tracks if CAPABILITIES has been received */
    private var capabilitiesReceived = false

    // Keep-alive timeout tracking
    private var lastPacketTime = System.currentTimeMillis()
    private val keepAliveTimeout = 15000L // 15 seconds
    private var currentRtt: Long = 0

    private val disconnected = AtomicBoolean(false)
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic PING messages every 10 seconds
        scheduledExecutor.scheduleAtFixedRate({
            if (running && !disconnected.get()) {
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
    @OptIn(ExperimentalSerializationApi::class)
    override fun run() {
        try {
            logger.info("ClientConnection.run() started for ${socket.remoteSocketAddress}")

            // Main packet processing loop
            while (running && !disconnected.get()) {
                // Check keep-alive timeout
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastPacketTime > keepAliveTimeout) {
                    logger.warning("Client connection timed out after ${keepAliveTimeout}ms of inactivity")
                    break // Terminate connection
                }

                // Read the framing layer
                val decompressedLen = reader.readInt()
                val compressedLen = reader.readInt()
                logger.info("Read frame header: decompressedLen=$decompressedLen, compressedLen=$compressedLen")

                if (decompressedLen <= 0 || compressedLen <= 0) {
                    logger.warning("Received non-positive frame size. Terminating connection.")
                    break
                }

                // Update last packet time after successful read
                lastPacketTime = currentTime

                val compressed = ByteArray(compressedLen)
                reader.readFully(compressed)
                logger.info("Read compressed data: ${compressed.size} bytes")

                // Log compressed bytes hex for debugging
                val compressedHex = compressed.take(32).joinToString("") { "%02X".format(it) }
                logger.info("Compressed hex (first 32 bytes): $compressedHex")

                try {
                    logger.fine("Decompressing ${compressed.size} bytes to expected $decompressedLen bytes...")
                    val decompressed: ByteArray
                    try {
                        decompressed = Zstd.decompress(compressed, decompressedLen)
                    } catch (t: Throwable) {
                        logger.severe("Zstd.decompress EXCEPTION: ${t.javaClass.name}: ${t.message}")
                        t.printStackTrace()
                        break
                    }
                    logger.fine("Decompression complete: ${decompressed.size} bytes")

                    if (decompressed.size != decompressedLen) {
                        logger.warning("Decompressed size mismatch. Expected $decompressedLen, got ${decompressed.size}. Terminating connection.")
                        break
                    }

                    // Debug: Log raw CBOR bytes for inspection
                    val hexPreview = decompressed.take(32).joinToString("") { "%02X".format(it) }
                    logger.fine("Decompressed CBOR: len=${decompressed.size}, hex=$hexPreview")

                    val mineChatPacket = try {
                        cbor.decodeFromByteArray(MineChatPacket, decompressed)
                    } catch (e: Exception) {
                        logger.severe("CBOR deserialization failed: ${e.message}")
                        e.printStackTrace()
                        break
                    }

                    logger.fine("Decoded packet: type=${mineChatPacket.packetType}, payloadClass=${mineChatPacket.payload::class.java.simpleName}")

                    // Update last packet time for any received packet
                    lastPacketTime = System.currentTimeMillis()

                    when (mineChatPacket.packetType) {
                        PacketTypes.LINK -> {
                            val payload = mineChatPacket.payload as LinkPayload
                            logger.info("Received LINK message: $payload")
                            handleAuth(payload)
                        }
                        PacketTypes.CAPABILITIES -> {
                            val payload = mineChatPacket.payload as CapabilitiesPayload
                            logger.info("Received CAPABILITIES message: $payload")
                            handleCapabilities(payload)
                        }
                        PacketTypes.CHAT_MESSAGE -> {
                            val payload = mineChatPacket.payload as ChatMessagePayload
                            logger.info("Received CHAT_MESSAGE message: $payload")
                            handleChat(payload)
                        }
                        PacketTypes.PING -> {
                            val payload = mineChatPacket.payload as PingPayload
                            logger.info("Received PING message: $payload")
                            handlePing(payload)
                        }
                        PacketTypes.PONG -> {
                            val payload = mineChatPacket.payload as PongPayload
                            logger.info("Received PONG message: $payload")
                            handlePong(payload)
                        }
                        else -> logger.warning("Unknown packet type: ${mineChatPacket.packetType}")
                    }
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
            logger.info("Client disconnected normally")
            return
        } catch (e: Exception) {
            if (running && !disconnected.get()) {
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
            logger.warning("Error sending AuthOkPayload: ${e.message}")
        }
    }

    private fun sendMessage(packetType: Int, payload: PingPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending PingPayload: ${e.message}")
        }
    }

    private fun sendMessage(packetType: Int, payload: PongPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending PongPayload: ${e.message}")
        }
    }

    private fun sendMessage(packetType: Int, payload: DisconnectPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending DisconnectPayload: ${e.message}")
        }
    }

    private fun <T : PacketPayload> createPacket(packetType: Int, payload: T): MineChatPacket {
        return MineChatPacket(packetType, payload)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sendPacket(mineChatPacket: MineChatPacket) {
        try {
            logger.info("sendPacket: serializing packetType=${mineChatPacket.packetType}")
            val serialized = cbor.encodeToByteArray(MineChatPacket, mineChatPacket)
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
        running = false
        disconnected.set(true)
        scheduledExecutor.shutdown()
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    fun formatPrefixed(message: Component): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(message)
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
                disconnect("Unknown client")
                return
            }
        }

        // Look up the link code
        val link = plugin.linkCodeStorage.find(linkCode)
        if (link == null) {
            logger.warning("Invalid link code: $linkCode")
            disconnect("Invalid link code")
            return
        }

        // Check if link code is expired (15 minutes)
        if (System.currentTimeMillis() - link.expiresAt > 15 * 60 * 1000) {
            logger.warning("Expired link code: $linkCode")
            plugin.linkCodeStorage.remove(linkCode)
            disconnect("Link code expired")
            return
        }

        // Check ban by Minecraft UUID
        banStorage.getBan(null, link.minecraftUuid?.toString())?.let {
            sendBannedMessage(it)
            return
        }

        // Delete the used link code
        plugin.linkCodeStorage.remove(linkCode)

        // Get or create client
        val existingClient = plugin.clientStorage.find(clientUuid, null)
        val client = if (existingClient != null) {
            existingClient.minecraftUuid = link.minecraftUuid
            existingClient.minecraftUsername = link.minecraftUsername
            existingClient
        } else {
            Client(
                clientUuid = clientUuid,
                minecraftUuid = link.minecraftUuid,
                minecraftUsername = link.minecraftUsername
            )
        }

        this.client = client

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

        client?.let {
            it.supportsComponents = payload.supports_components
            plugin.clientStorage.add(it)

            sendMessage(PacketTypes.AUTH_OK, AuthOkPayload())
            capabilitiesReceived = true

            if (it.supportsComponents) {
                broadcastMinecraft(ChatGradients.JOIN, "${it.minecraftUsername} has joined the chat.")
            } else {
                broadcastMinecraft(ChatGradients.AUTH, "${it.minecraftUsername} has successfully authenticated.")
            }

            logger.info("Client ${it.minecraftUsername} authenticated with capabilities: supportsComponents=${it.supportsComponents}")
        } ?: run {
            logger.warning("Received CAPABILITIES packet but client is null. This should not happen.")
            disconnect("Internal error: client not found.")
        }
    }

    private fun handleChat(payload: ChatMessagePayload) {
        val c = client ?: return

        // Process the chat message
        val format = payload.format
        val content = payload.content

        if (format == "commonmark") {
            // Parse commonmark and send to Minecraft
            val component = try {
                plugin.miniMessage.deserialize(content)
            } catch (e: Exception) {
                logger.warning("Failed to parse MiniMessage: ${e.message}")
                Component.text(content)
            }

            broadcastMinecraft(formatPrefixed(component))
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

    private fun sendBannedMessage(ban: Ban) {
        val reason = ban.reason ?: "You are banned"
        sendMessage(PacketTypes.DISCONNECT, DisconnectPayload(reason))
        close()
    }

    fun getClient(): Client? = client

    fun disconnect(reason: String) {
        sendMessage(PacketTypes.DISCONNECT, DisconnectPayload(reason))
        close()
    }

    private val logger: Logger
        get() = plugin.logger
}
