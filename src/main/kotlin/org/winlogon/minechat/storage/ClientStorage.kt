package org.winlogon.minechat.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.ClientTable

import java.util.UUID

class ClientStorage(
    private val databaseManager: DatabaseManager
) {
    private val clientCache: Cache<String, CachedClient> = Caffeine.newBuilder()
        .maximumSize(1000)
        .build()

    fun find(clientUuid: String?, minecraftUsername: String?): CachedClient? {
        if (clientUuid != null) {
            return clientCache.getIfPresent(clientUuid) ?: run {
                val result = databaseManager.syncQuery {
                    ClientTable.selectAll().where { ClientTable.clientUuid eq clientUuid }.firstOrNull()
                }.get()

                result?.let {
                    val cached = CachedClient(
                        it[ClientTable.clientUuid],
                        it[ClientTable.minecraftUuid]?.let { uuid -> UUID.fromString(uuid) },
                        it[ClientTable.minecraftUsername],
                        it[ClientTable.supportsComponents]
                    )
                    clientCache.put(clientUuid, cached)
                    cached
                }
            }
        }
        if (minecraftUsername != null) {
            return clientCache.asMap().values.find { it.minecraftUsername == minecraftUsername } ?: run {
                val result = databaseManager.syncQuery {
                    ClientTable.selectAll().where { ClientTable.minecraftUsername eq minecraftUsername }.firstOrNull()
                }.get()

                result?.let {
                    val cached = CachedClient(
                        it[ClientTable.clientUuid],
                        it[ClientTable.minecraftUuid]?.let { uuid -> UUID.fromString(uuid) },
                        it[ClientTable.minecraftUsername],
                        it[ClientTable.supportsComponents]
                    )
                    clientCache.put(cached.clientUuid, cached)
                    cached
                }
            }
        }
        return null
    }

    fun add(clientUuid: String, minecraftUuid: UUID?, minecraftUsername: String, supportsComponents: Boolean) {
        val existing = find(null, minecraftUsername)
        if (existing != null) {
            databaseManager.asyncQuery {
                ClientTable.update({ ClientTable.minecraftUsername eq minecraftUsername }) {
                    it[ClientTable.clientUuid] = clientUuid
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

    data class CachedClient(
        val clientUuid: String,
        val minecraftUuid: UUID?,
        val minecraftUsername: String,
        val supportsComponents: Boolean
    )
}
