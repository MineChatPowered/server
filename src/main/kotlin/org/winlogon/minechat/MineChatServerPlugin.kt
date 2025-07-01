package org.winlogon.minechat

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.Command

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import java.io.File
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

data class Config(
    val port: Int
)

class MineChatServerPlugin : JavaPlugin() {
    private var serverSocket: ServerSocket? = null
    private val connectedClients = ConcurrentLinkedQueue<ClientConnection>()
    private lateinit var linkCodeStorage: LinkCodeStorage
    private lateinit var clientStorage: ClientStorage
    private var isFolia = false

    private var port: Int = 25575
    private var expiryCodeMs = 300_000 // 5 minutes
    private var serverThread: Thread? = null
    @Volatile private var isServerRunning = false
    private val executorService = Executors.newVirtualThreadPerTaskExecutor()
    val gson = Gson()
    val miniMessage = MiniMessage.miniMessage()

    private fun generateLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
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
            "<gray>Your link code is: <code>. Use it in the client within <expiry_time>.</gray>",
            Placeholder.component("code", codeComponent),
            Placeholder.component("expiry_time", timeComponent)
        )
    }

    fun registerCommands() {
        val linkCommand = Commands.literal("link")
            .requires { sender -> sender.getExecutor() is Player } 
            .executes { ctx ->
                val sender = ctx.source.sender
                generateAndSendLinkCode(sender as Player)
                Command.SINGLE_SUCCESS
            }
            .build()

        val reloadCommand = Commands.literal("mchatreload")
            .requires { sender -> sender.getSender().hasPermission("minechat.reload") }
            .executes { ctx ->
                val sender = ctx.source.sender
                reloadConfig()
                port = config.getInt("port", 25575)
                expiryCodeMs = config.getInt("expiry-code-minutes", 5) * 60_000
                linkCodeStorage.load()
                clientStorage.load()
                sender.sendRichMessage("<gray>MineChat config and storage reloaded.</gray>")
                Command.SINGLE_SUCCESS
            }
            .build()

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(linkCommand, "Link your Minecraft account to the server")
            registrar.register(reloadCommand, "Reload MineChat's configuration")
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

        linkCodeStorage = LinkCodeStorage(dataFolder, gson)
        clientStorage = ClientStorage(dataFolder, gson)
        linkCodeStorage.load()
        clientStorage.load()

        registerCommands()

        serverSocket = ServerSocket(port)
        logger.info("Starting MineChat server on port $port")

        val saveTask = Runnable {
            linkCodeStorage.cleanupExpired()
            linkCodeStorage.save()
            clientStorage.save()
        }

        if (isFolia) {
            val scheduler = server.getAsyncScheduler()
            scheduler.runAtFixedRate(this, { _ -> saveTask.run() }, 1, 1, TimeUnit.MINUTES)
        } else {
            server.scheduler.runTaskTimer(this, saveTask, 0, 20 * 60)
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this, gson, miniMessage)
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
                        "message" to plainMsg
                    )
                )
                broadcastToClients(gson.toJson(message))
            }
        }, this)
    }

    override fun onDisable() {
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.close() }
        executorService.shutdownNow()
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        linkCodeStorage.save()
        clientStorage.save()
        try {
            serverThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun broadcastToClients(message: String) {
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
    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
}

data class LinkCode(
    val code: String,
    val minecraftUuid: UUID,
    val minecraftUsername: String,
    val expiresAt: Long
)

data class Client(
    val clientUuid: String,
    val minecraftUuid: UUID,
    val minecraftUsername: String
)

