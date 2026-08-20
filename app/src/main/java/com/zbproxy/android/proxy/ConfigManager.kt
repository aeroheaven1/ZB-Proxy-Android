package com.zbproxy.android.proxy

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ConfigManager(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile: File
        get() = File(context.filesDir, "ZBProxy.json")

    private val _config = MutableStateFlow(loadOrCreateConfig())
    val config: StateFlow<RootConfig> = _config.asStateFlow()

    private fun loadOrCreateConfig(): RootConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                gson.fromJson(content, RootConfig::class.java)
            } else {
                createDefaultConfig()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config, creating default", e)
            createDefaultConfig()
        }
    }

    fun createDefaultConfig(): RootConfig {
        val config = RootConfig(
            log = LogConfig(level = "debug"),
            services = mutableListOf(
                ServiceConfig(
                    name = "Hypixel-in",
                    targetAddress = "",
                    targetPort = 25565,
                    listen = 25565
                )
            ),
            router = RouterConfig(
                rules = mutableListOf(
                    RuleConfig(
                        type = "Always",
                        sniff = listOf("minecraft")
                    ),
                    RuleConfig(
                        type = "ServiceName",
                        parameter = "Hypixel-in",
                        rewrite = RewriteConfig(
                            minecraft = MinecraftRewriteConfig(
                                hostname = "mc.hypixel.net",
                                port = 25565
                            )
                        ),
                        outbound = "Hypixel-out"
                    )
                ),
                defaultOutbound = "RESET"
            ),
            outbounds = mutableListOf(
                OutboundConfig(
                    name = "Hypixel-out",
                    targetAddress = "mc.hypixel.net",
                    targetPort = 25565,
                    minecraft = MinecraftServiceConfig(
                        enableHostnameRewrite = true,
                        rewrittenHostname = "mc.hypixel.net",
                        onlineCount = OnlineCountConfig(max = 20, online = -1),
                        motdFavicon = "{DEFAULT_MOTD}",
                        motdDescription = "§d{NAME}§e, provided by §a§o{INFO}§r\\n§c§lProxy for §6§n{HOST}:{PORT}§r"
                    )
                )
            ),
            lists = mutableMapOf()
        )
        writeConfigToFile(config)
        return config
    }

    private fun writeConfigToFile(newConfig: RootConfig) {
        try {
            val json = gson.toJson(newConfig)
            configFile.writeText(json)
            _config.value = newConfig
            Log.i(TAG, "Config saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    suspend fun saveConfig(newConfig: RootConfig) {
        withContext(Dispatchers.IO) {
            writeConfigToFile(newConfig)
        }
    }

    suspend fun updateService(service: ServiceConfig) {
        val current = _config.value
        // Create a NEW RootConfig instance so StateFlow emits an update
        val newServices = current.services.toMutableList()
        val index = newServices.indexOfFirst { it.name == service.name }
        if (index >= 0) {
            newServices[index] = service
        } else {
            newServices.add(service)
        }
        saveConfig(current.copy(services = newServices))
    }

    suspend fun updateOutbound(outbound: OutboundConfig) {
        val current = _config.value
        // Create a NEW RootConfig instance so StateFlow emits an update
        val newOutbounds = current.outbounds.toMutableList()
        val index = newOutbounds.indexOfFirst { it.name == outbound.name }
        if (index >= 0) {
            newOutbounds[index] = outbound
        } else {
            newOutbounds.add(outbound)
        }
        saveConfig(current.copy(outbounds = newOutbounds))
    }

    suspend fun deleteService(name: String) {
        val current = _config.value
        saveConfig(
            current.copy(services = current.services.filterNot { it.name == name }.toMutableList())
        )
    }

    suspend fun deleteOutbound(name: String) {
        val current = _config.value
        saveConfig(
            current.copy(outbounds = current.outbounds.filterNot { it.name == name }.toMutableList())
        )
    }

    fun getServiceConfig(name: String): ServiceConfig? {
        return _config.value.services.find { it.name == name }
    }

    fun getOutboundConfig(name: String): OutboundConfig? {
        return _config.value.outbounds.find { it.name == name }
    }

    suspend fun reload() {
        _config.value = loadOrCreateConfig()
    }

    companion object {
        private const val TAG = "ZBProxy-Config"
    }
}