package org.winlogon.minechat.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class Mute(
    @Id var id: Long = 0,
    val minecraftUsername: String = "",
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
) {
    fun isExpired(): Boolean = expiresAt != null && System.currentTimeMillis() > expiresAt

    fun isPermanent(): Boolean = expiresAt == null
}
