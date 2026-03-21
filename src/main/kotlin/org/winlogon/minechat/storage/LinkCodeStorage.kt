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
    private val databaseManager: DatabaseManager
) : AutoCloseable {
    private var cleanupTask: Task? = null
    private val linkCodeCache: Cache<String, CachedLinkCode> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
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
        databaseManager.asyncQuery {
            LinkCodeTable.insert {
                it[LinkCodeTable.code] = code
                it[LinkCodeTable.minecraftUuid] = minecraftUuid.toString()
                it[LinkCodeTable.minecraftUsername] = minecraftUsername
                it[LinkCodeTable.expiresAt] = expiresAt
            }
        }
        linkCodeCache.put(code, CachedLinkCode(minecraftUuid, minecraftUsername, expiresAt))
    }

    fun find(code: String): CachedLinkCode? {
        return linkCodeCache.getIfPresent(code) ?: run {
            val result = databaseManager.syncQuery {
                LinkCodeTable.selectAll().where { LinkCodeTable.code eq code }.firstOrNull()
            }.get() ?: return null

            val cached = CachedLinkCode(
                UUID.fromString(result[LinkCodeTable.minecraftUuid]),
                result[LinkCodeTable.minecraftUsername],
                result[LinkCodeTable.expiresAt]
            )
            linkCodeCache.put(code, cached)
            cached
        }
    }

    fun remove(code: String) {
        linkCodeCache.invalidate(code)
        databaseManager.asyncQuery {
            LinkCodeTable.deleteWhere { LinkCodeTable.code eq code }
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
