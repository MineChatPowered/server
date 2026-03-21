@file:Suppress("UnstableApiUsage")

package org.winlogon.minechat

import org.bukkit.event.Listener
import org.winlogon.minechat.commands.MinechatCommands
import revxrsal.commands.Lamp
import revxrsal.commands.bukkit.BukkitLamp

class CommandRegister(private val services: PluginServices) : Listener {

    private var lamp: Lamp<*>? = null

    fun registerCommands() {
        lamp = BukkitLamp.builder(services.pluginInstance)
            .build()
            .also { lamp ->
                lamp.register(MinechatCommands(services))
            }
    }
}
