@file:Suppress("UnstableApiUsage")

package org.winlogon.minechat

import org.bukkit.event.Listener
import org.winlogon.minechat.commands.MinechatCommands

import revxrsal.commands.bukkit.BukkitLamp

class CommandRegister(private val services: PluginServices) : Listener {
    fun registerCommands() {
        BukkitLamp.builder(services.pluginInstance)
            .build()
            .register(MinechatCommands(services))
    }
}
