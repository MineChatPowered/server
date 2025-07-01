
class ClientStorage(private val dataFolder: File, private val gson: Gson) {
    private val file = File(dataFolder, "clients.json")
    private val clientCache = Caffeine.newBuilder().build<String, Client>().asMap()
    private var isDirty = AtomicBoolean(false)

    fun find(clientUuid: String): Client? = clientCache[clientUuid]

    fun add(client: Client) {
        clientCache[client.clientUuid] = client
        isDirty.set(true)
    }

    fun load() {
        if (!file.exists()) {
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<Client>>() {}.type
            val clients: List<Client> = gson.fromJson(json, type)
            clientCache.putAll(clients.associateBy { it.clientUuid })
        }
        isDirty.set(false)
    }

    fun save() {
        if (!isDirty.get()) return
        val clients = clientCache.values.toList()
        file.writeText(gson.toJson(clients))
        isDirty.set(false)
    }
}
