package org.winlogon.minechat

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType

import io.objectbox.BoxStore
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class MineChatServerPlugin : JavaPlugin() {
    private var serverSocket: ServerSocket? = null
    private val connectedClients = ConcurrentLinkedQueue<ClientConnection>()
    private lateinit var linkCodeStorage: LinkCodeStorage
    private lateinit var clientStorage: ClientStorage
    private lateinit var banStorage: BanStorage
    private lateinit var boxStore: BoxStore
    private var isFolia = false

    private var port: Int = 25575
    private var expiryCodeMs = 300_000 // 5 minutes
    private var serverThread: Thread? = null
    @Volatile private var isServerRunning = false
    private val executorService = Executors.newVirtualThreadPerTaskExecutor()
    val miniMessage = MiniMessage.miniMessage()

    private fun generateLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    fun getClientConnection(username: String): ClientConnection? {
        return connectedClients.find { it.getClient()?.minecraftUsername == username }
    }

    fun generateAndSendLinkCode(player: Player) {
        val code = generateLinkCode()

        val link = LinkCode(
            code = code,
            minecraftUuid = player.uniqueId,
            minecraftUsername = player.name,
            expiresAt = System.currentTimeMillis() + expiryCodeMs
        )
        linkCodeStorage.add(link)

        val codeComponent = Component.text(code, NamedTextColor.DARK_AQUA)
        val timeComponent = Component.text("${expiryCodeMs / 60000} minutes", NamedTextColor.DARK_GREEN)
        player.sendRichMessage(
            "<gray>Your link code is: </gray><code>. Use it in the client within <deadline>",
            Placeholder.component("code", codeComponent),
            Placeholder.component("deadline", timeComponent))
        )
    }

    fun registerCommands() {
        val linkCommand = Commands.literal("link")
            .requires { sender -> sender.executor is Player }
            .executes { ctx ->
                val sender = ctx.source.sender as Player
                generateAndSendLinkCode(sender)
                Command.SINGLE_SUCCESS
            }
            .build()

        val reloadCommand = Commands.literal("mchatreload")
            .requires { sender -> sender.sender.hasPermission("minechat.reload") }
            .executes { ctx ->
                reloadConfig()
                port = config.getInt("port", 25575)
                expiryCodeMs = config.getInt("expiry-code-minutes", 5) * 60_000
                ctx.source.sender.sendMessage(Component.text("MineChat config reloaded.").color(NamedTextColor.GREEN))
                Command.SINGLE_SUCCESS
            }
            .build()

        val banCommand = Commands.literal("minechat-ban")
            .requires { sender -> sender.sender.hasPermission("minechat.ban") }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    val client = clientStorage.find(null, playerName)
                    if (client == null) {
                        ctx.source.sender.sendMessage(Component.text("Player not found.").color(NamedTextColor.RED))
                        return@executes 0
                    }
                    val ban = Ban(minecraftUsername = playerName, reason = "Banned by an operator.")
                    banStorage.add(ban)
                    ctx.source.sender.sendMessage(Component.text("Banned $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        val unbanCommand = Commands.literal("minechat-unban")
            .requires { sender -> sender.sender.hasPermission("minechat.unban") }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    banStorage.remove(null, playerName)
                    ctx.source.sender.sendMessage(Component.text("Unbanned $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        val kickCommand = Commands.literal("minechat-kick")
            .requires { sender -> sender.sender.hasPermission("minechat.kick") }
            .then(Commands.argument("player", StringArgumentType.word())
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    val clientConnection = getClientConnection(playerName)
                    if (clientConnection == null) {
                        ctx.source.sender.sendMessage(Component.text("Player not found or not connected via MineChat.").color(NamedTextColor.RED))
                        return@executes 0
                    }
                    clientConnection.disconnect("Kicked by an operator.")
                    ctx.source.sender.sendMessage(Component.text("Kicked $playerName from MineChat.").color(NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                }
            )
            .build()

        this.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(linkCommand)
            registrar.register(reloadCommand)
            registrar.register(banCommand)
            registrar.register(unbanCommand)
            registrar.register(kickCommand)
        }
    }

    override fun onEnable() {
        isFolia = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        saveResource("config.yml", false)
        reloadConfig()

        port = config.getInt("port", 25575)
        expiryCodeMs = config.getInt("expiry-code-minutes", 5) * 60_000

        dataFolder.mkdirs()

        boxStore = MyObjectBox.builder().directory(dataFolder).build()
        linkCodeStorage = LinkCodeStorage(boxStore)
        clientStorage = ClientStorage(boxStore)
        banStorage = BanStorage(boxStore)

        registerCommands()

        val tlsEnabled = config.getBoolean("tls.enabled", false)

        if (tlsEnabled) {
            val keystoreFile = File(dataFolder, config.getString("tls.keystore", "keystore.jks"))
            val keystorePassword = config.getString("tls.keystore-password", "password")?.toCharArray()

            if (!keystoreFile.exists()) {
                logger.severe("Keystore file not found at ${keystoreFile.absolutePath}. Disabling TLS.")
                serverSocket = ServerSocket(port)
            } else {
                try {
                    val keyStore = KeyStore.getInstance("JKS")
                    keyStore.load(keystoreFile.inputStream(), keystorePassword)

                    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    keyManagerFactory.init(keyStore, keystorePassword)

                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(keyManagerFactory.keyManagers, null, null)

                    val sslServerSocketFactory = sslContext.serverSocketFactory
                    serverSocket = sslServerSocketFactory.createServerSocket(port)
                    logger.info("MineChat server started with TLS on port $port")
                } catch (e: Exception) {
                    logger.severe("Failed to initialize TLS: ${e.message}. Falling back to plain socket.")
                    e.printStackTrace()
                    serverSocket = ServerSocket(port)
                }
            }
        } else {
            serverSocket = ServerSocket(port)
            logger.info("Starting MineChat server on port $port")
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this, miniMessage)
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

        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                val plainMsg = PlainTextComponentSerializer.plainText().serialize(event.message())
                val message = mapOf(
                    "type" to "BROADCAST",
                    "payload" to mapOf(
                        "from" to event.player.name,
                        "message" to mapOf("text" to plainMsg)
                    )
                )
                broadcastToClients(message)
            }
        }, this)
    }

    override fun onDisable() {
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

    fun broadcastToClients(message: Map<String, Any>) {
        connectedClients.forEach { client ->
            try {
                client.sendMessage(message)
            } catch (e: Exception) {
                logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun getLinkCodeStorage(): LinkCodeStorage = linkCodeStorage
    fun getClientStorage(): ClientStorage = clientStorage
    fun getBanStorage(): BanStorage = banStorage
    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}
