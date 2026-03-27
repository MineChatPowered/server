@file:Suppress("UnstableApiUsage")
@file:OptIn(ExperimentalSerializationApi::class)

package org.winlogon.minechat

import com.charleskorn.kaml.Yaml
import com.github.luben.zstd.Zstd

import io.papermc.paper.event.player.AsyncChatEvent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
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
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import javax.net.ssl.KeyManager

class MineChatPlugin : JavaPlugin(), PluginServices {
    private var serverSocket: ServerSocket? = null
    private var isFolia: Boolean = false
    private lateinit var databaseManager: DatabaseManager
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
            "link" to Permission("minechat.link", "Generate a link code for MineChat client."),
        )
        server.pluginManager.addPermissions(permissions.values.toList())
    }

    override fun onEnable() {
        if (!::mineChatConfig.isInitialized) {
            mineChatConfig = MineChatConfig()
        }

        databaseManager = DatabaseManager(this)
        databaseManager.createTables()

        linkCodeStorage = LinkCodeStorage(this, databaseManager)
        clientStorage = ClientStorage(databaseManager)
        banStorage = BanStorage(databaseManager)
        muteStorage = MuteStorage(databaseManager)

        muteStorage.cleanExpired()

        val tls = mineChatConfig.tls

        CommandRegister(this).registerCommands()

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

            val delegateKeyManager = keyManagerFactory.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
                ?: throw IllegalStateException("No X509KeyManager found")

            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(arrayOf<KeyManager>(delegateKeyManager), arrayOf<TrustManager>(trustManager), null)

            val sslServerSocketFactory = sslContext.serverSocketFactory
            serverSocket = sslServerSocketFactory.createServerSocket(mineChatConfig.port)

            (serverSocket as SSLServerSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3")
            }

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
        broadcastChatMessage("commonmark", markdownMsg, event.message())
    }

    override fun onDisable() {
        logger.info("Disabling MineChatPlugin")
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.sendSystemDisconnect(SystemDisconnectReason.SHUTDOWN, "Server is shutting down.") }
        //if (::linkCodeStorage.isInitialized) {
            linkCodeStorage.close()
        //}
        executorService.shutdownNow()
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        //if (::databaseManager.isInitialized) {
            databaseManager.close()
        //}
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

    /**
     * Broadcasts a chat message to all connected clients, respecting each client's capabilities.
     */
    override fun broadcastChatMessage(format: String, content: String, component: Component) {
        connectedClients.forEach { client ->
            try {
                client.sendChatMessage(format, content, component)
            } catch (e: Exception) {
                logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}
