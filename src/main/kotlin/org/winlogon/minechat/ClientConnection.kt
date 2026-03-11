package org.winlogon.minechat

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

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
    @PublishedApi
    internal val logger = plugin.logger

    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @PublishedApi
    internal val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    internal val reader = DataInputStream(socket.getInputStream())
    @PublishedApi
    internal val writer = DataOutputStream(socket.getOutputStream())
    private var client: Client? = null
    private var running = true
    private var linkAuthCompleted = false // Tracks if LINK_OK has been sent
    private var capabilitiesReceived = false // Tracks if CAPABILITIES has been received
    
    // Keep-alive timeout tracking
    private var lastPacketTime = System.currentTimeMillis()

    /** Calculated Round Trip Time in milliseconds */
    var currentRtt: Long = -1
        private set

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

    @OptIn(ExperimentalSerializationApi::class)
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

                    val compressedLen = reader.readInt()
                    if (compressedLen <= 0) {
                        logger.warning("Received non-positive compressed length: $compressedLen. Terminating connection.")
                        break // Terminate connection
                    }
                    
                    // Update last packet time after successful read
                    lastPacketTime = currentTime

                    val compressed = ByteArray(compressedLen)
                    reader.readFully(compressed)

                    val decompressed = Zstd.decompress(compressed, decompressedLen)
                    if (decompressed.size != decompressedLen) {
                        logger.warning("Decompressed size mismatch. Expected $decompressedLen, got ${decompressed.size}. Terminating connection.")
                        break // Terminate connection
                    }

                    val mineChatPacket = cbor.decodeFromByteArray<MineChatPacket>(decompressed)
                    
                    // Update last packet time for any received packet
                    lastPacketTime = System.currentTimeMillis()

                    when (mineChatPacket.packetType) {
                        PacketTypes.LINK -> {
                            val payload = cbor.decodeFromByteArray<LinkPayload>(mineChatPacket.payload)
                            logger.fine("Received LINK message: $payload")
                            handleAuth(payload)
                        }
                        PacketTypes.CAPABILITIES -> {
                            val payload = cbor.decodeFromByteArray<CapabilitiesPayload>(mineChatPacket.payload)
                            logger.fine("Received CAPABILITIES message: $payload")
                            handleCapabilities(payload)
                        }
                        PacketTypes.CHAT_MESSAGE -> {
                            val payload = cbor.decodeFromByteArray<ChatMessagePayload>(mineChatPacket.payload)
                            logger.fine("Received CHAT_MESSAGE message: $payload")
                            handleChat(payload)
                        }
                        PacketTypes.PING -> {
                            val payload = cbor.decodeFromByteArray<PingPayload>(mineChatPacket.payload)
                            logger.fine("Received PING message: $payload")
                            handlePing(payload)
                        }
                        PacketTypes.PONG -> {
                            val payload = cbor.decodeFromByteArray<PongPayload>(mineChatPacket.payload)
                            logger.fine("Received PONG message: $payload")
                            handlePong(payload)
                        }
                        else -> logger.warning("Unknown packet type: ${mineChatPacket.packetType}")
                    }
                } catch (_: SocketTimeoutException) {
                    // This is expected due to keep-alive timeout checking, continue loop
                    continue
                } catch (e: Exception) {
                    if (running && !disconnected.get()) {
                        logger.warning("Client error during packet processing: ${e.message}")
                    }
                    break
                }
            }
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

        // Per spec: send LINK_OK, wait for CAPABILITIES, then send AUTH_OK
        sendMessage(
            PacketTypes.LINK_OK,
            LinkOkPayload(minecraftUuid = link.minecraftUuid.toString())
        )
        linkAuthCompleted = true

        // Now wait for CAPABILITIES before completing auth
        logger.fine("Sent LINK_OK, waiting for CAPABILITIES...")
    }

    private fun handleExistingClientAuth(clientUuid: String) {
        val client = plugin.clientStorage.find(clientUuid, null)
            ?: return disconnect("Client not registered")

        plugin.banStorage.getBan(null, client.minecraftUsername)?.let {
            sendBannedMessage(it)
            return
        }

        this.client = client

        // Per spec: send LINK_OK, wait for CAPABILITIES, then send AUTH_OK
        sendMessage(
            PacketTypes.LINK_OK,
            LinkOkPayload(minecraftUuid = client.minecraftUuid.toString())
        )
        linkAuthCompleted = true

        // Now wait for CAPABILITIES before completing auth
        logger.fine("Sent LINK_OK for reconnection, waiting for CAPABILITIES...")
    }

    private fun handleChat(payload: ChatMessagePayload) {
        client?.let {
            val message = if (payload.format == "commonmark") {
                MarkdownSerializer.markdown().deserialize(payload.content)
            } else {
                miniMessage.deserialize(payload.content)
            }
            
            val usernamePlaceholder = Component.text(it.minecraftUsername, NamedTextColor.DARK_GREEN)
            val formattedMsg = miniMessage.deserialize(
                "<gray><sender><dark_gray>:</dark_gray> <message></gray>",
                Placeholder.component("sender", usernamePlaceholder),
                Placeholder.component("message", message)
            )
            val finalMsg = formatPrefixed(formattedMsg)
            Bukkit.broadcast(finalMsg)
            
            val markdownContent = MarkdownSerializer.markdown().serialize(message)
            val chatMessagePayload = ChatMessagePayload(
                format = "commonmark",
                content = markdownContent
            )
            plugin.broadcastToClients(PacketTypes.CHAT_MESSAGE, chatMessagePayload)
        }
    }

    private fun handleCapabilities(payload: CapabilitiesPayload) {
        // Per spec, CAPABILITIES must come after LINK_OK
        if (!linkAuthCompleted) {
            logger.warning("Received CAPABILITIES packet before LINK_OK. Disconnecting.")
            disconnect("Received CAPABILITIES before completing linking.")
            return
        }

        if (capabilitiesReceived) {
            logger.warning("Received duplicate CAPABILITIES packet. Ignoring.")
            return
        }

        client?.let {
            it.supportsComponents = payload.supportsComponents
            plugin.clientStorage.add(it) // Update the client in storage
            
            // Send AUTH_OK to complete the authentication flow
            sendMessage(PacketTypes.AUTH_OK, AuthOkPayload())
            capabilitiesReceived = true
            
            // Send join message
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

    private fun handlePing(payload: PingPayload) {
        // Respond with a PONG packet, echoing the timestamp
        sendMessage(PacketTypes.PONG, PongPayload(payload.timestampMs))
        logger.fine("Responded to PING from client with timestamp ${payload.timestampMs}")
    }

    private fun handlePong(payload: PongPayload) {
        val now = System.currentTimeMillis()
        currentRtt = now - payload.timestampMs
        logger.fine("Received PONG from client with RTT: ${currentRtt}ms")
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> sendMessage(packetType: Int, payload: T) {
        try {
            val payloadBytes = cbor.encodeToByteArray(payload)
            val mineChatPacket = MineChatPacket(packetType, payloadBytes)
            val serialized = cbor.encodeToByteArray(mineChatPacket)
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
            logger.warning("Error sending message: ${e.message}")
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
}
