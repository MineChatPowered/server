package org.winlogon.minechat

import io.objectbox.Box
import io.objectbox.BoxStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinkCodeStorage(private val boxStore: BoxStore) {
    private val linkCodeBox: Box<LinkCode> = boxStore.boxFor(LinkCode::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule cleanup of expired link codes every minute
        scheduler.scheduleAtFixedRate({
            cleanupExpired()
        }, 0, 1, TimeUnit.MINUTES)
    }

    fun add(linkCode: LinkCode) {
        linkCodeBox.put(linkCode)
    }

    fun find(code: String): LinkCode? {
        return linkCodeBox.query(LinkCode_.code.equal(code)).build().findFirst()
    }

    fun remove(code: String) {
        linkCodeBox.query(LinkCode_.code.equal(code)).build().remove()
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        linkCodeBox.query(LinkCode_.expiresAt.less(now)).build().remove()
    }

    fun close() {
        scheduler.shutdown()
    }
}
