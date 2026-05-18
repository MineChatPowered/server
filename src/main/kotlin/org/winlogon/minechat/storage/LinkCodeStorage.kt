package org.winlogon.minechat.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.LinkCodeTable
import org.winlogon.asynccraftr.AsyncCraftr
import org.winlogon.asynccraftr.task.Task

import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.time.Duration

class LinkCodeStorage(
    plugin: JavaPlugin,
    private val databaseManager: DatabaseManager,
    expiryMinutes: Int = 5
) : AutoCloseable {
    private var cleanupTask: Task? = null
    private val linkCodeCache: Cache<String, CachedLinkCode> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(expiryMinutes.toLong()))
        .build()

    init {
        cleanupTask = AsyncCraftr.runAsyncTaskTimer(
            plugin,
            { cleanupExpired() },
            Duration.ZERO,
            Duration.ofMinutes(1)
        )
    }

    fun add(code: String, minecraftUuid: UUID, minecraftUsername: String, expiresAt: Long) {
        val normalizedCode = code.uppercase()
        databaseManager.asyncQuery {
            LinkCodeTable.insert {
                it[LinkCodeTable.code] = normalizedCode
                it[LinkCodeTable.minecraftUuid] = minecraftUuid.toString()
                it[LinkCodeTable.minecraftUsername] = minecraftUsername
                it[LinkCodeTable.expiresAt] = expiresAt
            }
        }
        linkCodeCache.put(normalizedCode, CachedLinkCode(minecraftUuid, minecraftUsername, expiresAt))
    }

    fun find(code: String): CachedLinkCode? {
        val normalizedCode = code.uppercase()
        return linkCodeCache.getIfPresent(normalizedCode) ?: run {
            val result = databaseManager.syncQuery {
                LinkCodeTable.selectAll().where { LinkCodeTable.code eq normalizedCode }.firstOrNull()
            }.get() ?: return null

            val cached = CachedLinkCode(
                UUID.fromString(result[LinkCodeTable.minecraftUuid]),
                result[LinkCodeTable.minecraftUsername],
                result[LinkCodeTable.expiresAt]
            )
            linkCodeCache.put(normalizedCode, cached)
            cached
        }
    }

    fun remove(code: String) {
        val normalizedCode = code.uppercase()
        linkCodeCache.invalidate(normalizedCode)
        databaseManager.asyncQuery {
            LinkCodeTable.deleteWhere { LinkCodeTable.code eq normalizedCode }
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        databaseManager.asyncQuery {
            LinkCodeTable.deleteWhere { LinkCodeTable.expiresAt less now }
        }
        linkCodeCache.asMap().values.removeIf { it.expiresAt < now }
    }

    override fun close() {
        cleanupTask?.cancel()
    }

    data class CachedLinkCode(
        val minecraftUuid: UUID,
        val minecraftUsername: String,
        val expiresAt: Long
    )
}
