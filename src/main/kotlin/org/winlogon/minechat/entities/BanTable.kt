package org.winlogon.minechat.entities

import org.jetbrains.exposed.v1.core.Table

object BanTable : Table("bans") {
    val id = long("id").autoIncrement()
    val clientUuid = text("client_uuid").nullable()
    val minecraftUuid = text("minecraft_uuid").nullable()
    val minecraftUsername = text("minecraft_username").nullable()
    val reason = text("reason").nullable()
    val timestamp = long("timestamp").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)
}
