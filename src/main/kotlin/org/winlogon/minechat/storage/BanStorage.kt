package org.winlogon.minechat.storage

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.BanTable

import java.util.UUID

class BanStorage(
    private val databaseManager: DatabaseManager
) {
    fun add(clientUuid: String?, minecraftUuid: UUID?, minecraftUsername: String?, reason: String?, expiresAt: Long? = null) {
        val existing = if (clientUuid != null) getBan(clientUuid, null) else minecraftUsername?.let { getBan(null, it) }
        if (existing != null) {
            val condition = if (clientUuid != null) BanTable.clientUuid eq clientUuid else BanTable.minecraftUsername eq minecraftUsername!!
            databaseManager.asyncQuery {
                BanTable.update({ condition }) {
                    it[BanTable.minecraftUuid] = minecraftUuid?.toString()
                    it[BanTable.reason] = reason
                    it[BanTable.timestamp] = System.currentTimeMillis()
                    it[BanTable.expiresAt] = expiresAt
                }
            }
        } else {
            databaseManager.asyncQuery {
                BanTable.insert {
                    it[BanTable.clientUuid] = clientUuid
                    it[BanTable.minecraftUuid] = minecraftUuid?.toString()
                    it[BanTable.minecraftUsername] = minecraftUsername
                    it[BanTable.reason] = reason
                    it[BanTable.timestamp] = System.currentTimeMillis()
                    it[BanTable.expiresAt] = expiresAt
                }
            }
        }
    }

    fun remove(clientUuid: String?, minecraftUsername: String?) {
        if (clientUuid != null) {
            databaseManager.asyncQuery {
                BanTable.deleteWhere { BanTable.clientUuid eq clientUuid }
            }
        }
        if (minecraftUsername != null) {
            databaseManager.asyncQuery {
                BanTable.deleteWhere { BanTable.minecraftUsername eq minecraftUsername }
            }
        }
    }

    fun getBan(clientUuid: String?, minecraftUsername: String?): BanInfo? {
        val condition = when {
            clientUuid != null -> BanTable.clientUuid eq clientUuid
            minecraftUsername != null -> BanTable.minecraftUsername eq minecraftUsername
            else -> return null
        }

        val result = databaseManager.syncQuery {
            BanTable.selectAll()
                .where { condition }
                .firstOrNull()
        }.get()

        return result?.toBanInfo()?.takeIf { !it.isExpired() }
    }

    fun getBanByMinecraftUuid(minecraftUuid: UUID): BanInfo? {
        val result = databaseManager.syncQuery {
            BanTable.selectAll()
                .where { BanTable.minecraftUuid eq minecraftUuid.toString() }
                .firstOrNull()
        }.get()

        return result?.toBanInfo()?.takeIf { !it.isExpired() }
    }

    fun cleanExpired() {
        val now = System.currentTimeMillis()
        databaseManager.asyncQuery {
            BanTable.deleteWhere { BanTable.expiresAt.isNotNull() and (BanTable.expiresAt less now) }
        }
    }

    private fun ResultRow.toBanInfo() = BanInfo(
        clientUuid = this[BanTable.clientUuid],
        minecraftUuid = this[BanTable.minecraftUuid]?.let(UUID::fromString),
        minecraftUsername = this[BanTable.minecraftUsername],
        reason = this[BanTable.reason],
        timestamp = this[BanTable.timestamp],
        expiresAt = this[BanTable.expiresAt]
    )

    data class BanInfo(
        val clientUuid: String?,
        val minecraftUuid: UUID?,
        val minecraftUsername: String?,
        val reason: String?,
        val timestamp: Long,
        val expiresAt: Long?
    ) {
        fun isExpired(): Boolean = expiresAt != null && System.currentTimeMillis() > expiresAt
        fun isPermanent(): Boolean = expiresAt == null
    }
}
