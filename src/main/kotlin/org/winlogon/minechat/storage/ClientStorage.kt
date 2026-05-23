package org.winlogon.minechat.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.ClientTable

import java.util.UUID


data class CachedClient(
    val clientUuid: String,
    val minecraftUuid: UUID?,
    val minecraftUsername: String,
    val supportsComponents: Boolean
)

class ClientStorage(
    private val databaseManager: DatabaseManager
) {
    private val clientCache: Cache<String, CachedClient> = Caffeine.newBuilder()
        .maximumSize(1000)
        .build()

    private fun ResultRow.toCachedClient(): CachedClient = CachedClient(
        this[ClientTable.clientUuid],
        this[ClientTable.minecraftUuid]?.let { UUID.fromString(it) },
        this[ClientTable.minecraftUsername],
        this[ClientTable.supportsComponents]
    )

    private fun cacheAndReturn(row: ResultRow, cacheKey: String): CachedClient {
        val client = row.toCachedClient()
        clientCache.put(cacheKey, client)
        return client
    }

    fun find(clientUuid: String?, minecraftUsername: String?): CachedClient? {
        if (clientUuid != null) {
            return clientCache.getIfPresent(clientUuid) ?: run {
                databaseManager.syncQuery {
                    ClientTable.selectAll().where { ClientTable.clientUuid eq clientUuid }.firstOrNull()
                }.get()?.let { cacheAndReturn(it, clientUuid) }
            }
        }
        if (minecraftUsername != null) {
            return clientCache.asMap().values.find { it.minecraftUsername == minecraftUsername } ?: run {
                databaseManager.syncQuery {
                    ClientTable.selectAll().where { ClientTable.minecraftUsername eq minecraftUsername }.firstOrNull()
                }.get()?.let { cacheAndReturn(it, it[ClientTable.clientUuid]) }
            }
        }
        return null
    }

    fun add(clientUuid: String, minecraftUuid: UUID?, minecraftUsername: String, supportsComponents: Boolean) {
        val existing = find(clientUuid, null)
        if (existing != null) {
            databaseManager.asyncQuery {
                ClientTable.update({ ClientTable.clientUuid eq clientUuid }) {
                    it[ClientTable.minecraftUuid] = minecraftUuid?.toString()
                    it[ClientTable.minecraftUsername] = minecraftUsername
                    it[ClientTable.supportsComponents] = supportsComponents
                }
            }
            clientCache.put(clientUuid, CachedClient(clientUuid, minecraftUuid, minecraftUsername, supportsComponents))
        } else {
            databaseManager.asyncQuery {
                ClientTable.insert {
                    it[ClientTable.clientUuid] = clientUuid
                    it[ClientTable.minecraftUuid] = minecraftUuid?.toString()
                    it[ClientTable.minecraftUsername] = minecraftUsername
                    it[ClientTable.supportsComponents] = supportsComponents
                }
            }
            clientCache.put(clientUuid, CachedClient(clientUuid, minecraftUuid, minecraftUsername, supportsComponents))
        }
    }

    fun findByUsername(minecraftUsername: String): List<CachedClient> {
        val cached = clientCache.asMap().values.filter { it.minecraftUsername == minecraftUsername }
        if (cached.isNotEmpty()) return cached

        return databaseManager.syncQuery {
            ClientTable.selectAll().where { ClientTable.minecraftUsername eq minecraftUsername }
        }.get().map { cacheAndReturn(it, it[ClientTable.clientUuid]) }
    }
}
