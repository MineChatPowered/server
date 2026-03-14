package org.winlogon.minechat.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

import org.winlogon.minechat.storage.UuidConverter

import java.util.UUID

@Entity
data class Ban(
    @Id var id: Long = 0,
    val clientUuid: String? = null,
    @Convert(converter = UuidConverter::class, dbType = String::class)
    val minecraftUuid: UUID? = null,
    val minecraftUsername: String? = null,
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
