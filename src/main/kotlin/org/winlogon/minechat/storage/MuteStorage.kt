package org.winlogon.minechat.storage

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

import org.winlogon.minechat.DatabaseManager
import org.winlogon.minechat.entities.MuteTable

class MuteStorage(
    private val databaseManager: DatabaseManager
) {
    fun add(minecraftUsername: String, reason: String?, expiresAt: Long?) {
        val existing = getMute(minecraftUsername)
        if (existing != null) {
            databaseManager.asyncQuery {
                MuteTable.update({ MuteTable.minecraftUsername eq minecraftUsername }) {
                    it[MuteTable.reason] = reason
                    it[MuteTable.expiresAt] = expiresAt
                }
            }
        } else {
            databaseManager.asyncQuery {
                MuteTable.insert {
                    it[MuteTable.minecraftUsername] = minecraftUsername
                    it[MuteTable.reason] = reason
                    it[MuteTable.createdAt] = System.currentTimeMillis()
                    it[MuteTable.expiresAt] = expiresAt
                }
            }
        }
    }

    fun remove(minecraftUsername: String) {
        databaseManager.asyncQuery {
            MuteTable.deleteWhere { MuteTable.minecraftUsername eq minecraftUsername }
        }
    }

    fun getMute(minecraftUsername: String): MuteInfo? {
        return databaseManager.syncQuery {
            MuteTable.selectAll().where { MuteTable.minecraftUsername eq minecraftUsername }.firstOrNull()
        }.get()?.let {
            MuteInfo(
                minecraftUsername = it[MuteTable.minecraftUsername],
                reason = it[MuteTable.reason],
                createdAt = it[MuteTable.createdAt],
                expiresAt = it[MuteTable.expiresAt]
            )
        }
    }

    fun isMuted(minecraftUsername: String): Boolean {
        val mute = getMute(minecraftUsername)
        return mute != null && !mute.isExpired()
    }

    fun cleanExpired() {
        val now = System.currentTimeMillis()
        databaseManager.asyncQuery {
            MuteTable.deleteWhere { MuteTable.expiresAt.isNotNull() and (MuteTable.expiresAt less now) }
        }
    }

    data class MuteInfo(
        val minecraftUsername: String,
        val reason: String?,
        val createdAt: Long,
        val expiresAt: Long?
    ) {
        fun isExpired(): Boolean = expiresAt != null && System.currentTimeMillis() > expiresAt
        fun isPermanent(): Boolean = expiresAt == null
    }
}
