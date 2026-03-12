package org.winlogon.minechat

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
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
import java.util.concurrent.ExecutorService
import java.util.logging.Logger

class ClientConnection(
    private val socket: Socket,
    private val plugin: MineChatServerPlugin,
    private val executorService: ExecutorService
) : Runnable {
    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @PublishedApi
    internal val cbor = createCbor()

    internal val reader = DataInputStream(socket.getInputStream())
    @PublishedApi
    internal val writer = DataOutputStream(socket.getOutputStream())
    private var client: Client? = null
    private var running = true
    private var linkAuthCompleted = false // Tracks if LINK_OK has been sent
    private var capabilitiesReceived = false // Tracks if CAPABILITIES has been received
    
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

    @OptIn(ExperimentalSerializationApi::class)
    override fun run() {
        try {
            logger.info("Client connected: ${socket.remoteSocketAddress}")
            
            // Main packet processing loop
            while (running && !disconnected.get()) {
                // Check keep-alive timeout
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastPacketTime > keepAliveTimeout) {
                    logger.warning("Client connection timed out after ${keepAliveTimeout}ms of inactivity")
                    break // Terminate connection
                }

                // Read the framing layer (per spec section 4)
                val decompressedLen = reader.readInt()
                val compressedLen = reader.readInt()
                
                if (decompressedLen <= 0 || compressedLen <= 0) {
                    logger.warning("Received non-positive frame size. Terminating connection.")
                    break
                }
                
                // Update last packet time after successful read
                lastPacketTime = currentTime

                val compressed = ByteArray(compressedLen)
                reader.readFully(compressed)

                val decompressed = Zstd.decompress(compressed, decompressedLen)
                if (decompressed.size != decompressedLen) {
                    logger.warning("Decompressed size mismatch. Expected $decompressedLen, got ${decompressed.size}. Terminating connection.")
                    break
                }

                val mineChatPacket = cbor.decodeFromByteArray<MineChatPacket>(decompressed)
                
                // Update last packet time for any received packet
                lastPacketTime = System.currentTimeMillis()

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
        } catch (_: SocketTimeoutException) {
            // This is expected due to keep-alive timeout checking, continue loop
        } catch (e: Exception) {
            if (running && !disconnected.get()) {
                logger.warning("Client error in run loop: ${e.message}")
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

    private fun sendMessage(packetType: Int, payload: CapabilitiesPayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending CapabilitiesPayload: ${e.message}")
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

    private fun sendMessage(packetType: Int, payload: ChatMessagePayload) {
        try {
            val mineChatPacket = createPacket(packetType, payload)
            sendPacket(mineChatPacket)
        } catch (e: Exception) {
            logger.warning("Error sending ChatMessagePayload: ${e.message}")
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
        val serialized = cbor.encodeToByteArray(mineChatPacket)
        val compressed = Zstd.compress(serialized)
        
        if (serialized.size > Int.MAX_VALUE || compressed.size > Int.MAX_VALUE) {
            throw IllegalArgumentException("Packet too large")
        }
        
        writer.writeInt(serialized.size)
        writer.writeInt(compressed.size)
        writer.write(compressed)
        writer.flush()
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
        logger.fine("Handling auth with payload: $payload")

        val banStorage = plugin.banStorage
        val clientUuid = payload.client_uuid
        val linkCode = payload.linking_code

        // Check ban by client UUID first
        banStorage.getBan(clientUuid, null)?.let {
            sendBannedMessage(it)
            return
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
        logger.fine("Sent LINK_OK, waiting for CAPABILITIES...")
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
            
            logger.fine("Client ${it.minecraftUsername} authenticated with capabilities: supportsComponents=${it.supportsComponents}")
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
                MiniMessage.miniMessage().deserialize(content)
            } catch (e: Exception) {
                logger.warning("Failed to parse MiniMessage: ${e.message}")
                Component.text(content)
            }
            
            broadcastMinecraft(formatPrefixed(component))
        }
    }

    private fun handlePing(payload: PingPayload) {
        sendMessage(PacketTypes.PONG, PongPayload(payload.timestamp_ms))
        logger.fine("Responded to PING from client with timestamp ${payload.timestamp_ms}")
    }

    private fun handlePong(payload: PongPayload) {
        val now = System.currentTimeMillis()
        currentRtt = now - payload.timestamp_ms
        logger.fine("Received PONG from client with RTT: ${currentRtt}ms")
    }

    private fun broadcastMinecraft(gradient: Pair<String, String>, message: String) {
        val gradientColor = "<gradient:${gradient.first}:${gradient.second}>"
        val component = MiniMessage.miniMessage().deserialize(gradientColor)
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
