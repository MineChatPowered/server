@file:Suppress("UnstableApiUsage")
@file:OptIn(ExperimentalSerializationApi::class)

package org.winlogon.minechat

import com.charleskorn.kaml.Yaml
import com.github.luben.zstd.Zstd

import io.objectbox.BoxStore
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.serialization.ExperimentalSerializationApi

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
import org.winlogon.minechat.entities.MyObjectBox
import org.winlogon.minechat.storage.BanStorage
import org.winlogon.minechat.storage.ClientStorage
import org.winlogon.minechat.storage.LinkCodeStorage
import org.winlogon.minechat.storage.MuteStorage

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.serialization.decodeFromString

class MineChatPlugin : JavaPlugin(), PluginServices {
    private var serverSocket: ServerSocket? = null
    private var isFolia: Boolean = false
    private lateinit var boxStore: BoxStore
    @Volatile private var isServerRunning: Boolean = false
    private var serverThread: Thread? = null
    private val executorService = Executors.newVirtualThreadPerTaskExecutor()

    override val connectedClients = ConcurrentLinkedQueue<ClientConnection>()
    lateinit var loggerProvider: PluginLoggerProvider
    override lateinit var linkCodeStorage: LinkCodeStorage
    override lateinit var clientStorage: ClientStorage
    override lateinit var banStorage: BanStorage
    override lateinit var muteStorage: MuteStorage
    override lateinit var mineChatConfig: MineChatConfig
    override lateinit var permissions: Map<String, Permission>
    override val miniMessage = MiniMessage.miniMessage()
    override val pluginInstance: JavaPlugin
        get() = this

    private fun loadConfig(): MineChatConfig {
        val configFile = File(dataFolder, "config.yml")
        return try {
            Yaml.default.decodeFromString(configFile.readText())
        } catch (e: Exception) {
            logger.severe("Failed to load config.yml: ${e.message}. Using default config.")
            MineChatConfig()
        }
    }

    override fun reloadConfigAndDependencies() {
        saveResource("config.yml", false)
        reloadConfig()
        mineChatConfig = loadConfig()
    }

    override fun generateRandomLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    override fun onLoad() {
        isFolia = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        logger.info("Running on ${if (isFolia) "Paper with Folia" else "Paper"}")
        loggerProvider = PluginLoggerProvider(this)

        reloadConfigAndDependencies()
        dataFolder.mkdirs()

        permissions = mapOf(
            "reload" to Permission("minechat.reload", "Reloads the MineChat configuration."),
            "ban" to Permission("minechat.ban", "Bans a player from the MineChat server."),
            "unban" to Permission("minechat.unban", "Removes a ban from a player."),
            "mute" to Permission("minechat.mute", "Mutes a player from the MineChat server."),
            "warn" to Permission("minechat.warn", "Warns a player in the MineChat server."),
            "kick" to Permission("minechat.kick", "Kicks a player from the MineChat server."),
        )
        server.pluginManager.addPermissions(permissions.values.toList())
    }

    override fun onEnable() {
        boxStore = MyObjectBox.builder().directory(dataFolder).build()
        linkCodeStorage = LinkCodeStorage(boxStore)
        clientStorage = ClientStorage(boxStore)
        banStorage = BanStorage(boxStore)
        muteStorage = MuteStorage(boxStore)

        muteStorage.cleanExpired()

        CommandRegister(this).registerCommands()
        val tls = mineChatConfig.tls

        if (!tls.enabled) {
            logger.severe("MineChat server cannot start: TLS is disabled in config.yml, but it is mandatory.")
            return
        }

        val keystoreFile = File(dataFolder, tls.keystore)
        val keystorePassword = tls.keystorePassword.toCharArray()

        // Generate keystore if it doesn't exist
        if (!keystoreFile.exists()) {
            logger.info("Generating TLS certificate for MineChat server...")
            try {
                CertificateGenerator.generateKeystore(
                    keystoreFile.toPath(),
                    keystorePassword,
                    commonName = "MineChat Server"
                )
                logger.info("TLS certificate generated successfully: ${keystoreFile.absolutePath}")
                logger.warning("IMPORTANT: Clients will need to re-link due to the new certificate.")
            } catch (e: Exception) {
                logger.severe("Failed to generate TLS certificate: ${e.message}")
                e.printStackTrace()
                return
            }
        }

        try {
            val keyStore = CertificateGenerator.loadKeystore(keystoreFile.toPath(), keystorePassword)
                ?: throw IllegalStateException("Failed to load keystore")

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, keystorePassword)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, null)

            val sslServerSocketFactory = sslContext.serverSocketFactory
            serverSocket = sslServerSocketFactory.createServerSocket(mineChatConfig.port)

            // Enforce TLS 1.3
            (serverSocket as SSLServerSocket).enabledProtocols = arrayOf("TLSv1.3")

            logger.info("MineChat server started with TLS 1.3 on port ${mineChatConfig.port}")
        } catch (e: Exception) {
            logger.severe("MineChat server cannot start: Failed to initialize TLS: ${e.message}")
            logger.severe("If this is a certificate error, delete the keystore file and restart to regenerate.")
            e.printStackTrace()
            return
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this)
                        connectedClients.add(connection)
                        executorService.submit(connection)
                    }
                } catch (e: Exception) {
                    if (!isServerRunning) break
                    logger.warning("Error accepting client: ${e.message}")
                }
            }
            logger.info("MineChat server socket thread stopped.")
        }
        serverThread?.start()

        // Register chat listener and player join handler
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                this@MineChatPlugin.onChat(event)
            }

            @EventHandler
            fun onPlayerJoin(event: PlayerJoinEvent) {
                muteStorage.cleanExpired()
            }
        }, this)
    }

    fun onChat(event: AsyncChatEvent) {
        val playerName = event.player.name

        if (muteStorage.isMuted(playerName)) {
            val mute = muteStorage.getMute(playerName)
            val message = when {
                mute?.isPermanent() == true -> "You are permanently muted."
                mute?.expiresAt != null -> {
                    val remaining = (mute.expiresAt - System.currentTimeMillis()) / 60000
                    "You are muted. Expires in ${remaining.toInt()} minutes."
                }
                else -> "You are muted."
            }
            event.isCancelled = true
            event.player.sendMessage(Component.text(message, NamedTextColor.RED))
            return
        }

        val markdownMsg = MarkdownSerializer.markdown().serialize(event.message())
        val chatMessagePayload = ChatMessagePayload(
            format = "commonmark",
            content = markdownMsg
        )
        broadcastToClients(PacketTypes.CHAT_MESSAGE, chatMessagePayload)
    }

    override fun onDisable() {
        logger.info("Disabling MineChatPlugin")
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.sendSystemDisconnect(SystemDisconnectReason.SHUTDOWN, "Server is shutting down.") }
        linkCodeStorage.close()
        executorService.shutdownNow()
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        boxStore.close()
        try {
            serverThread?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val cbor = createCbor()

    override fun broadcastToClients(packetType: Int, payload: PacketPayload) {
        connectedClients.forEach { client ->
            try {
                val mineChatPacket = MineChatPacket(packetType, payload)
                val serialized = cbor.encodeToByteArray(MineChatPacket, mineChatPacket)
                val compressed = Zstd.compress(serialized)

                client.writer.writeInt(serialized.size)
                client.writer.writeInt(compressed.size)
                client.writer.write(compressed)
                client.writer.flush()
            } catch (e: Exception) {
                logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}
