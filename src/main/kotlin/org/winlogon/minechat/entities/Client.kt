package org.winlogon.minechat.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

import org.winlogon.minechat.storage.UuidConverter

import java.util.UUID

@Entity
data class Client(
    @Id var id: Long = 0,
    var clientUuid: String = "",
    @Convert(converter = UuidConverter::class, dbType = String::class)
    var minecraftUuid: UUID? = null,
    var minecraftUsername: String = "",
    var supportsComponents: Boolean = false
)
