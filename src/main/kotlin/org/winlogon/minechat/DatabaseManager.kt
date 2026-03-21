package org.winlogon.minechat

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.winlogon.asynccraftr.AsyncCraftr
import org.winlogon.minechat.entities.BanTable
import org.winlogon.minechat.entities.ClientTable
import org.winlogon.minechat.entities.LinkCodeTable
import org.winlogon.minechat.entities.MuteTable

import org.bukkit.plugin.java.JavaPlugin

import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable

class DatabaseManager(private val plugin: JavaPlugin) {

    companion object {
        private const val TIMEOUT_SECONDS = 30L
    }

    private val database: Database

    init {
        val dbFile = plugin.dataFolder.resolve("minechat.db")
        dbFile.parentFile?.mkdirs()

        database = Database.connect("jdbc:sqlite:${dbFile.absolutePath}")

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    }

    fun <T> asyncQuery(block: () -> T): CompletableFuture<T> {
        return AsyncCraftr.runAsync(plugin, Callable {
            transaction {
                block()
            }
        }) ?: CompletableFuture.failedFuture(IllegalStateException("Failed to schedule async task"))
    }

    fun <T> syncQuery(block: () -> T): CompletableFuture<T> {
        return AsyncCraftr.runSync(plugin, Callable {
            transaction {
                block()
            }
        }) ?: CompletableFuture.failedFuture(IllegalStateException("Failed to schedule sync task"))
    }

    fun createTables() {
        transaction {
            SchemaUtils.create(
                LinkCodeTable,
                ClientTable,
                MuteTable,
                BanTable
            )
        }
    }

    fun close() {
        TransactionManager.closeAndUnregister(database)
    }
}
