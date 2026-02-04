package org.winlogon.minechat

import com.charleskorn.kaml.Yaml

import io.objectbox.BoxStore
import io.papermc.paper.event.player.AsyncChatEvent
import org.winlogon.minechat.storage.BanStorage
import org.winlogon.minechat.storage.ClientStorage
import org.winlogon.minechat.storage.LinkCodeStorage
import org.winlogon.minechat.entities.MyObjectBox

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit

import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

import kotlinx.serialization.decodeFromString

class MineChatServerPlugin : JavaPlugin(), MineChatPluginServices {
    private var serverSocket: ServerSocket? = null
    private var isFolia: Boolean = false
    private lateinit var boxStore: BoxStore
    @Volatile private var isServerRunning: Boolean = false
    private var serverThread: Thread? = null
    private val executorService = Executors.newCachedThreadPool()

    override val connectedClients = ConcurrentLinkedQueue<ClientConnection>()
    lateinit var loggerProvider: PluginLoggerProvider
    override lateinit var linkCodeStorage: LinkCodeStorage
    override lateinit var clientStorage: ClientStorage
    override lateinit var banStorage: BanStorage
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
            loggerProvider.logger.severe("Failed to load config.yml: ${e.message}. Using default config.")
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

        loggerProvider = PluginLoggerProvider(this)

        reloadConfigAndDependencies()
        dataFolder.mkdirs()

        permissions = mapOf(
            "reload" to Permission("minechat.reload", "Reloads the MineChat configuration."),
            "ban" to Permission("minechat.ban", "Bans a player from the MineChat server."),
        )
        Bukkit.getPluginManager().addPermissions(permissions.values.toList())
    }

    override fun onEnable() {
        boxStore = MyObjectBox.builder().directory(dataFolder).build()
        linkCodeStorage = LinkCodeStorage(boxStore)
        clientStorage = ClientStorage(boxStore)
        banStorage = BanStorage(boxStore)

        MineChatCommandRegister(this).registerCommands()

        if (!mineChatConfig.tls.enabled) {
            loggerProvider.logger.severe("MineChat server cannot start: TLS is disabled in config.yml. TLS is mandatory as per specification.")
            return
        }

        val keystoreFile = File(dataFolder, mineChatConfig.tls.keystore)
        val keystorePassword = mineChatConfig.tls.keystorePassword.toCharArray()

        if (!keystoreFile.exists()) {
            loggerProvider.logger.severe("MineChat server cannot start: Keystore file not found at ${keystoreFile.absolutePath}. TLS is mandatory as per specification.")
            return
        }

        try {
            val keyStore = KeyStore.getInstance("JKS")
            keyStore.load(keystoreFile.inputStream(), keystorePassword)

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, keystorePassword)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, null)

            val sslServerSocketFactory = sslContext.serverSocketFactory
            serverSocket = sslServerSocketFactory.createServerSocket(mineChatConfig.port)
            loggerProvider.logger.info("MineChat server started with TLS on port ${mineChatConfig.port}")
        } catch (e: Exception) {
            loggerProvider.logger.severe("MineChat server cannot start: Failed to initialize TLS: ${e.message}. TLS is mandatory as per specification.")
            e.printStackTrace()
            return
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        loggerProvider.logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this, miniMessage)
                        connectedClients.add(connection)
                        executorService.submit(connection)
                    }
                } catch (e: Exception) {
                    if (!isServerRunning) break
                    loggerProvider.logger.warning("Error accepting client: ${e.message}")
                }
            }
            loggerProvider.logger.info("MineChat server socket thread stopped.")
        }
        serverThread?.start()

        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                val plainMsg = PlainTextComponentSerializer.plainText().serialize(event.message())
                // TODO: actually format message as CommonMark
                val chatMessagePayload = ChatMessagePayload(
                    format = "commonmark",
                    content = plainMsg
                )
                broadcastToClients(PacketTypes.CHAT_MESSAGE, chatMessagePayload)
            }
        }, this)
    }

    override fun onDisable() {
        loggerProvider.logger.info("Disabling MineChatServerPlugin")
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.disconnect("Server is shutting down.") }
        executorService.shutdownNow()
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        boxStore.close()
        try {
            serverThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun broadcastToClients(packetType: Int, payload: Any) {
        connectedClients.forEach { client ->
            try {
                client.sendMessage(packetType, payload)
            } catch (e: Exception) {
                loggerProvider.logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}
