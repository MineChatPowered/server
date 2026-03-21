package org.winlogon.minechat.storage

import io.objectbox.Box
import io.objectbox.BoxStore

import org.winlogon.minechat.entities.Ban
import org.winlogon.minechat.entities.Ban_

class BanStorage(boxStore: BoxStore) {
    private val banBox: Box<Ban> = boxStore.boxFor(Ban::class.java)

    fun add(ban: Ban) {
        banBox.put(ban)
    }

    fun remove(clientUuid: String?, minecraftUsername: String?) {
        if (clientUuid != null) {
            banBox.query(Ban_.clientUuid.equal(clientUuid)).build().remove()
        }
        if (minecraftUsername != null) {
            banBox.query(Ban_.minecraftUsername.equal(minecraftUsername)).build().remove()
        }
    }

    fun getBan(clientUuid: String?, minecraftUsername: String?): Ban? {
        if (clientUuid != null) {
            val ban = banBox.query(Ban_.clientUuid.equal(clientUuid)).build().findFirst()
            if (ban != null) {
                return ban
            }
        }
        if (minecraftUsername != null) {
            val ban = banBox.query(Ban_.minecraftUsername.equal(minecraftUsername)).build().findFirst()
            if (ban != null) {
                return ban
            }
        }
        return null
    }
}
