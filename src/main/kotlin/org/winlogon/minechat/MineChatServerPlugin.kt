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
import kotlinx.serialization.serializer

class MineChatServerPlugin : JavaPlugin(), MineChatPluginServices {
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
            loggerProvider.logger.severe("MineChat server cannot start: TLS is disabled in config.yml, but it is mandatory.")
            return
        }

        val keystoreFile = File(dataFolder, mineChatConfig.tls.keystore)
        val keystorePassword = mineChatConfig.tls.keystorePassword.toCharArray()

        if (!keystoreFile.exists()) {
            loggerProvider.logger.severe("MineChat server cannot start: TLS is mandatory, but no keystore file was found at ${keystoreFile.absolutePath}.")
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
                        val connection = ClientConnection(socket, this, executorService)
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

        // Register chat listener
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                this@MineChatServerPlugin.onChat(event)
            }
        }, this)
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val markdownMsg = MarkdownSerializer.markdown().serialize(event.message())
        val chatMessagePayload = ChatMessagePayload(
            format = "commonmark",
            content = markdownMsg
        )
        broadcastToClients(PacketTypes.CHAT_MESSAGE, chatMessagePayload)
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
                val serialized = cbor.encodeToByteArray(MineChatPacketSerializer, mineChatPacket)
                val compressed = com.github.luben.zstd.Zstd.compress(serialized)
                
                client.writer.writeInt(serialized.size)
                client.writer.writeInt(compressed.size)
                client.writer.write(compressed)
                client.writer.flush()
            } catch (e: Exception) {
                loggerProvider.logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}
