package org.winlogon.minechat.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import io.objectbox.Box
import io.objectbox.BoxStore

import org.winlogon.minechat.entities.LinkCode
import org.winlogon.minechat.entities.LinkCode_

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinkCodeStorage(boxStore: BoxStore) : Closeable {
    private val linkCodeBox: Box<LinkCode> = boxStore.boxFor(LinkCode::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val linkCodeCache: Cache<String, LinkCode> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()

    init {
        // Schedule cleanup of expired link codes every minute
        scheduler.scheduleAtFixedRate({
            cleanupExpired()
        }, 0, 1, TimeUnit.MINUTES)
    }

    fun add(linkCode: LinkCode) {
        linkCodeBox.put(linkCode)
        linkCodeCache.put(linkCode.code, linkCode)
    }

    fun find(code: String): LinkCode? {
        return linkCodeCache.getIfPresent(code) ?: run {
            val linkCode = linkCodeBox.query(LinkCode_.code.equal(code)).build().findFirst()
            linkCode?.let { linkCodeCache.put(code, it) }
            linkCode
        }
    }

    fun remove(code: String) {
        linkCodeCache.invalidate(code)
        linkCodeBox.query(LinkCode_.code.equal(code)).build().remove()
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        linkCodeBox.query(LinkCode_.expiresAt.less(now)).build().remove()
        linkCodeCache.asMap().values.removeIf { it.expiresAt < now }
    }

    override fun close() {
        scheduler.shutdown()
    }
}
