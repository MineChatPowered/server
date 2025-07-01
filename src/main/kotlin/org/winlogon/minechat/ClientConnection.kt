class ClientConnection(
    private val socket: java.net.Socket,
    private val plugin: MineChatServerPlugin,
    private val gson: Gson,
    private val miniMessage: MiniMessage
) : Runnable {
    object ChatGradients {
        val JOIN = Pair("#27AE60", "#2ECC71")
        val LEAVE = Pair("#C0392B", "#E74C3C")
        val AUTH = Pair("#8E44AD", "#9B59B6")
        val INFO = Pair("#2980B9", "#3498DB")
    }

    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()
    private var client: Client? = null
    private var running = true

    private fun broadcastMinecraft(colors: Pair<String, String>?, message: String) {
        val formattedMessage = colors?.let { "<gradient:${it.first}:${it.second}>$message</gradient>" } ?: message
        val finalMessage = miniMessage.deserialize(formattedMessage)
        Bukkit.broadcast(formatPrefixed(finalMessage))
    }

    override fun run() {
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val json = gson.fromJson(line, JsonObject::class.java)
                when (json.get("type").asString) {
                    "AUTH" -> handleAuth(json.getAsJsonObject("payload"))
                    "CHAT" -> handleChat(json.getAsJsonObject("payload"))
                    "DISCONNECT" -> break
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Client error: ${e.message}")
        } finally {
            client?.let {
                broadcastMinecraft(ChatGradients.LEAVE, "${it.minecraftUsername} has left the chat.")
                plugin.broadcastToClients(
                    gson.toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "leave",
                                "username" to it.minecraftUsername,
                                "message" to "${it.minecraftUsername} has left the chat."
                            )
                        )
                    )
                )
            }
            close()
            plugin.removeClient(this)
        }
    }

    private fun handleAuth(payload: JsonObject) {
        val clientUuid = payload.get("client_uuid").asString
        val linkCode = payload.get("link_code").asString

        if (linkCode.isNotEmpty()) {
            val link = plugin.getLinkCodeStorage().find(linkCode)
            if (link != null && link.expiresAt > System.currentTimeMillis()) {
                val client = Client(clientUuid, link.minecraftUuid, link.minecraftUsername)
                plugin.getClientStorage().add(client)
                plugin.getLinkCodeStorage().remove(link.code)
                this.client = client
                sendMessage(
                    gson.toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "success",
                                "message" to "Linked to ${link.minecraftUsername}",
                                "minecraft_uuid" to link.minecraftUuid.toString(),
                                "username" to link.minecraftUsername
                            )
                        )
                    )
                )
                broadcastMinecraft(ChatGradients.AUTH, "${link.minecraftUsername} has successfully authenticated.")
                plugin.broadcastToClients(
                    gson.toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "join",
                                "username" to link.minecraftUsername,
                                "message" to "${link.minecraftUsername} has joined the chat."
                            )
                        )
                    )
                )
            } else {
                sendMessage(
                    gson.toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "failure",
                                "message" to "Invalid or expired link code"
                            )
                        )
                    )
                )
            }
        } else {
            val client = plugin.getClientStorage().find(clientUuid)
            if (client != null) {
                this.client = client
                sendMessage(
                    gson.toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "success",
                                "message" to "Welcome back, ${client.minecraftUsername}",
                                "minecraft_uuid" to client.minecraftUuid.toString(),
                                "username" to client.minecraftUsername
                            )
                        )
                    )
                )
                broadcastMinecraft(ChatGradients.JOIN,"${client.minecraftUsername} has joined the chat.")
                plugin.broadcastToClients(
                    gson.toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "join",
                                "username" to client.minecraftUsername,
                                "message" to "${client.minecraftUsername} has joined the chat."
                            )
                        )
                    )
                )
            } else {
                sendMessage(
                    gson.toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "failure",
                                "message" to "Client not registered"
                            )
                        )
                    )
                )
            }
        }
    }

    private fun handleChat(payload: JsonObject) {
        client?.let {
            val message = payload.get("message").asString
            val usernamePlaceholder = Component.text(it.minecraftUsername, NamedTextColor.DARK_GREEN)
            val messagePladeholder = Component.text(message)
            val formattedMsg = miniMessage.deserialize(
                "<gray><sender><dark_gray>:</dark_gray> <message></gray>",
                Placeholder.component("sender", usernamePlaceholder),
                Placeholder.component("message", messagePladeholder)
            )
            val finalMsg = formatPrefixed(formattedMsg)
            Bukkit.broadcast(finalMsg)
            plugin.broadcastToClients(
                gson.toJson(
                    mapOf(
                        "type" to "BROADCAST",
                        "payload" to mapOf(
                            "from" to "[MineChat] ${it.minecraftUsername}",
                            "message" to message
                        )
                    )
                )
            )
        }
    }

    fun sendMessage(message: String) {
        try {
            writer.write(message)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            plugin.logger.warning("Error sending message: ${e.message}")
        }
    }

    fun close() {
        running = false
        socket.close()
    }

    fun formatPrefixed(message: Component): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(message)
    }
}
