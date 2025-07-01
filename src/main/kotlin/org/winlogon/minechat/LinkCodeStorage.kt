class LinkCodeStorage(private val dataFolder: File, private val gson: Gson) {
    private val file = File(dataFolder, "link_codes.json")
    private val linkCodeCache = Caffeine.newBuilder().build<String, LinkCode>().asMap()
    private var isDirty = AtomicBoolean(false)

    fun add(linkCode: LinkCode) {
        linkCodeCache[linkCode.code] = linkCode
        isDirty.set(true)
    }

    fun find(code: String): LinkCode? = linkCodeCache[code]

    fun remove(code: String) {
        linkCodeCache.remove(code)
        isDirty.set(true)
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        var modified = false
        val iterator = linkCodeCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.expiresAt <= now) {
                iterator.remove()
                modified = true
            }
        }
        if (modified) isDirty.set(true)
    }

    fun load() {
        if (!file.exists()) {
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<LinkCode>>() {}.type
            val codes: List<LinkCode> = gson.fromJson(json, type)
            linkCodeCache.putAll(codes.associateBy { it.code })
        }
        isDirty.set(false)
    }

    fun save() {
        if (!isDirty.get()) return
        val codes = linkCodeCache.values.toList()
        file.writeText(gson.toJson(codes))
        isDirty.set(false)
    }
}

