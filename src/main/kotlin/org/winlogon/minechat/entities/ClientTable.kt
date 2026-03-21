package org.winlogon.minechat.entities

import org.jetbrains.exposed.v1.core.Table

object ClientTable : Table("clients") {
    val id = long("id").autoIncrement()
    val clientUuid = text("client_uuid")
    val minecraftUuid = text("minecraft_uuid").nullable()
    val minecraftUsername = text("minecraft_username")
    val supportsComponents = bool("supports_components").clientDefault { false }

    override val primaryKey = PrimaryKey(id)
}
