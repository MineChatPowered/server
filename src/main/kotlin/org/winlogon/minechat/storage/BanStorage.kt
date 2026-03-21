package org.winlogon.minechat.storage

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.BanTable

import java.util.UUID

class BanStorage(
    private val databaseManager: DatabaseManager
) {
    fun add(clientUuid: String?, minecraftUuid: UUID?, minecraftUsername: String?, reason: String?) {
        databaseManager.asyncQuery {
            BanTable.insert {
                it[BanTable.clientUuid] = clientUuid
                it[BanTable.minecraftUuid] = minecraftUuid?.toString()
                it[BanTable.minecraftUsername] = minecraftUsername
                it[BanTable.reason] = reason
                it[BanTable.timestamp] = System.currentTimeMillis()
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

        return result?.toBanInfo()
    }

    private fun ResultRow.toBanInfo() = BanInfo(
        clientUuid = this[BanTable.clientUuid],
        minecraftUuid = this[BanTable.minecraftUuid]?.let(UUID::fromString),
        minecraftUsername = this[BanTable.minecraftUsername],
        reason = this[BanTable.reason],
        timestamp = this[BanTable.timestamp]
    )

    data class BanInfo(
        val clientUuid: String?,
        val minecraftUuid: UUID?,
        val minecraftUsername: String?,
        val reason: String?,
        val timestamp: Long
    )
}
