package org.winlogon.minechat.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

import org.winlogon.minechat.storage.UuidConverter

import java.util.UUID

@Entity
data class LinkCode(
    @Id var id: Long = 0,
    val code: String,
    @Convert(converter = UuidConverter::class, dbType = String::class)
    val minecraftUuid: UUID,
    val minecraftUsername: String,
    val expiresAt: Long
)
