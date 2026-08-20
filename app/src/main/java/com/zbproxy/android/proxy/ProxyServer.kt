package com.zbproxy.android.proxy

import com.zbproxy.android.util.LogCollector
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ProxyStatus(
    val isRunning: Boolean = false,
    val listeningPort: Int = 0,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    val totalBytesTransferred: Long = 0,
    val services: List<ServiceStatus> = emptyList()
)

data class ServiceStatus(
    val name: String,
    val listenPort: Int,
    val targetAddress: String,
    val targetPort: Int,
    val activeConnections: Int,
    val isRunning: Boolean
)

class ProxyServer private constructor(
    private val configManager: ConfigManager,
    private val logCollector: LogCollector
) {
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    private val activeConnections = ConcurrentHashMap<String, TcpRelay>()
    private val connectionCounter = AtomicInteger(0)
    private val totalConnections = AtomicInteger(0)
    private val runningServices = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var _status = ProxyStatus()
    val status: ProxyStatus get() = _status

    suspend fun start() {
        if (isRunning.compareAndSet(false, true)) {
            // Recreate scope so the server can be restarted after stop()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            logCollector.info("Proxy", "Starting ZBProxy server...")
            val config = configManager.config.value

            config.services.forEach { serviceConfig ->
                startService(serviceConfig)
            }

            updateStatus()
            logCollector.info("Proxy", "ZBProxy started successfully")
        }
    }

    private suspend fun startService(serviceConfig: ServiceConfig) {
        withContext(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(serviceConfig.listen))

                runningServices[serviceConfig.name] = true
                serverSockets[serviceConfig.name] = serverSocket

                logCollector.info("Proxy",
                    "Service [${serviceConfig.name}] listening on 0.0.0.0:${serviceConfig.listen}")

                // Resolve target: use service target if set, otherwise find matching outbound
                val targetAddress: String
                val targetPort: Int
                val minecraftRewrite: String?
                val minecraftPort: Int?

                // Check if there's a matching outbound with Minecraft rewrite
                val matchingRule = configManager.config.value.router.rules.find { rule ->
                    when (rule.type) {
                        "ServiceName" -> {
                            val param = rule.parameter
                            when (param) {
                                is String -> param == serviceConfig.name
                                is com.google.gson.internal.LinkedTreeMap<*, *> -> {
                                    // Sometimes Gson deserializes as a map
                                    false
                                }
                                else -> false
                            }
                        }
                        "Always" -> true
                        else -> false
                    }
                }

                val ruleRewrite = matchingRule?.rewrite
                val minecraftRewriteConfig = ruleRewrite?.minecraft
                if (minecraftRewriteConfig != null) {
                    val outbound = matchingRule?.let { rule ->
                        configManager.config.value.outbounds.find { it.name == rule.outbound }
                    }
                    targetAddress = outbound?.targetAddress ?: minecraftRewriteConfig.hostname
                    targetPort = minecraftRewriteConfig.port
                    minecraftRewrite = minecraftRewriteConfig.hostname
                    minecraftPort = minecraftRewriteConfig.port
                } else if (serviceConfig.targetAddress.isNotEmpty()) {
                    targetAddress = serviceConfig.targetAddress
                    targetPort = serviceConfig.targetPort
                    minecraftRewrite = serviceConfig.minecraft?.rewrittenHostname
                    minecraftPort = serviceConfig.targetPort
                } else {
                    // Find first matching outbound
                    val outbound = configManager.config.value.outbounds.firstOrNull()
                    targetAddress = outbound?.targetAddress ?: "mc.hypixel.net"
                    targetPort = outbound?.targetPort ?: 25565
                    minecraftRewrite = outbound?.minecraft?.rewrittenHostname
                    minecraftPort = outbound?.targetPort ?: 25565
                }

                // Accept connections
                scope.launch {
                    acceptConnections(
                        serverSocket,
                        serviceConfig.name,
                        targetAddress,
                        targetPort,
                        minecraftRewrite,
                        minecraftPort
                    )
                }
            } catch (e: Exception) {
                logCollector.error("Proxy",
                    "Failed to start service [${serviceConfig.name}]: ${e.message}")
                runningServices[serviceConfig.name] = false
            }
        }
    }

    private suspend fun acceptConnections(
        serverSocket: ServerSocket,
        serviceName: String,
        targetAddress: String,
        targetPort: Int,
        minecraftRewrite: String?,
        minecraftPort: Int?
    ) {
        while (isRunning.get() && !serverSocket.isClosed) {
            try {
                val clientSocket = withContext(Dispatchers.IO) {
                    serverSocket.accept()
                }

                clientSocket.tcpNoDelay = true
                clientSocket.keepAlive = true

                val connId = connectionCounter.incrementAndGet().toString()
                totalConnections.incrementAndGet()

                logCollector.info("Proxy",
                    "[$connId] New connection from ${clientSocket.inetAddress.hostAddress}:${clientSocket.port} on service [$serviceName]")

                val relay = TcpRelay(
                    clientSocket = clientSocket,
                    targetAddress = targetAddress,
                    targetPort = targetPort,
                    minecraftHostnameRewrite = minecraftRewrite,
                    minecraftPortRewrite = minecraftPort,
                    logCollector = logCollector,
                    connectionId = connId,
                    scope = scope
                )

                activeConnections[connId] = relay

                scope.launch {
                    relay.start()
                    activeConnections.remove(connId)
                    updateStatus()
                }

                updateStatus()
            } catch (e: java.nio.channels.ClosedByInterruptException) {
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    logCollector.error("Proxy",
                        "Error accepting connection on [$serviceName]: ${e.message}")
                }
            }
        }
    }

    suspend fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            logCollector.info("Proxy", "Stopping ZBProxy server...")

            // Close all server sockets
            serverSockets.values.forEach { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            serverSockets.clear()

            // Stop all active relays
            activeConnections.values.forEach { relay ->
                try { relay.stop() } catch (_: Exception) {}
            }
            activeConnections.clear()

            runningServices.clear()
            scope.cancel()
            updateStatus()

            logCollector.info("Proxy", "ZBProxy stopped")
        }
    }

    private fun updateStatus() {
        val config = configManager.config.value
        _status = ProxyStatus(
            isRunning = isRunning.get(),
            activeConnections = activeConnections.size,
            totalConnections = totalConnections.get().toLong(),
            services = config.services.map { svc ->
                ServiceStatus(
                    name = svc.name,
                    listenPort = svc.listen,
                    targetAddress = svc.targetAddress.ifEmpty {
                        config.outbounds.firstOrNull()?.targetAddress ?: "N/A"
                    },
                    targetPort = svc.targetPort.takeIf { it > 0 } ?: 25565,
                    activeConnections = activeConnections.size,
                    isRunning = runningServices[svc.name] ?: false
                )
            }
        )
    }

    fun destroy() {
        scope.cancel()
        serverSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        activeConnections.values.forEach { try { it.stop() } catch (_: Exception) {} }
    }

    companion object {
        @Volatile
        private var instance: ProxyServer? = null

        fun getInstance(configManager: ConfigManager, logCollector: LogCollector): ProxyServer {
            return instance ?: synchronized(this) {
                instance ?: ProxyServer(configManager, logCollector).also { instance = it }
            }
        }
    }
}