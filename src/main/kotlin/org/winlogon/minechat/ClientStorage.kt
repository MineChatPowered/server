package org.winlogon.minechat

import io.objectbox.Box
import io.objectbox.BoxStore

class ClientStorage(boxStore: BoxStore) {
    private val clientBox: Box<Client> = boxStore.boxFor(Client::class.java)

    fun find(clientUuid: String?, minecraftUsername: String?): Client? {
        if (clientUuid != null) {
            return clientBox.query(Client_.clientUuid.equal(clientUuid)).build().findFirst()
        }
        if (minecraftUsername != null) {
            return clientBox.query(Client_.minecraftUsername.equal(minecraftUsername)).build().findFirst()
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
        } else {
            clientBox.put(client)
        }
    }
}
