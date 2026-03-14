package org.winlogon.minechat.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import io.objectbox.Box
import io.objectbox.BoxStore
import org.winlogon.minechat.entities.Client
import org.winlogon.minechat.entities.Client_

class ClientStorage(boxStore: BoxStore) {
    private val clientBox: Box<Client> = boxStore.boxFor(Client::class.java)
    private val clientCache: Cache<String, Client> = Caffeine.newBuilder().build()

    /**
     * Finds a client by either client UUID or Minecraft username.
     *
     * @param clientUuid The client UUID to search for (optional)
     * @param minecraftUsername The Minecraft username to search for (optional)
     * @return The matching Client, or null if not found. At least one parameter must be provided.
     */
    fun find(clientUuid: String?, minecraftUsername: String?): Client? {
        if (clientUuid != null) {
            return clientCache.getIfPresent(clientUuid) ?: run {
                val client = clientBox.query(Client_.clientUuid.equal(clientUuid)).build().findFirst()
                client?.let { clientCache.put(clientUuid, it) }
                client
            }
        }
        if (minecraftUsername != null) {
            return clientCache.asMap().values.find { it.minecraftUsername == minecraftUsername } ?: run {
                val client = clientBox.query(Client_.minecraftUsername.equal(minecraftUsername)).build().findFirst()
                client?.let { clientCache.put(it.clientUuid, it) }
                client
            }
        }
        return null
    }

    fun add(client: Client) {
        // Check if a client with the same minecraft username already exists
        val existing = find(null, client.minecraftUsername)
        if (existing != null) {
            // Update existing client's uuid
            existing.clientUuid = client.clientUuid
            clientBox.put(existing)
            clientCache.put(existing.clientUuid, existing)
        } else {
            clientBox.put(client)
            clientCache.put(client.clientUuid, client)
        }
    }
}
