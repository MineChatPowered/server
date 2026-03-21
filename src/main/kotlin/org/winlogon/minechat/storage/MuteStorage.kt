package org.winlogon.minechat.storage

import io.objectbox.Box
import io.objectbox.BoxStore

import org.winlogon.minechat.entities.Mute
import org.winlogon.minechat.entities.Mute_

class MuteStorage(boxStore: BoxStore) {
    private val muteBox: Box<Mute> = boxStore.boxFor(Mute::class.java)

    fun add(mute: Mute) {
        muteBox.put(mute)
    }

    fun remove(minecraftUsername: String) {
        muteBox.query(Mute_.minecraftUsername.equal(minecraftUsername)).build().remove()
    }

    fun getMute(minecraftUsername: String): Mute? {
        return muteBox.query(Mute_.minecraftUsername.equal(minecraftUsername))
            .build()
            .findFirst()
    }

    fun isMuted(minecraftUsername: String): Boolean {
        val mute = getMute(minecraftUsername)
        return mute != null && !mute.isExpired()
    }

    fun cleanExpired() {
        val now = System.currentTimeMillis()
        val allMutes = muteBox.all
        val expired = allMutes.filter { it.expiresAt != null && it.expiresAt < now }
        expired.forEach { muteBox.remove(it) }
    }
}
