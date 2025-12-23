package org.winlogon.minechat

import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

class PluginLoggerProvider(plugin: JavaPlugin) {
    val logger: Logger = plugin.logger
}
