package org.winlogon.minechat.entities

import org.jetbrains.exposed.v1.core.Table

object MuteTable : Table("mutes") {
    val id = long("id").autoIncrement()
    val minecraftUsername = text("minecraft_username").uniqueIndex()
    val reason = text("reason").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val expiresAt = long("expires_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
