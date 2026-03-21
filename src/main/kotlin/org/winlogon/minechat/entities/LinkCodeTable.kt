package org.winlogon.minechat.entities

import org.jetbrains.exposed.v1.core.Table

object LinkCodeTable : Table("link_codes") {
    val id = long("id").autoIncrement()
    val code = text("code").uniqueIndex()
    val minecraftUuid = text("minecraft_uuid")
    val minecraftUsername = text("minecraft_username")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(id)
}
