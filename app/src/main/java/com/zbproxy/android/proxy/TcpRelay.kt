package com.zbproxy.android.proxy

import com.zbproxy.android.util.LogCollector
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP relay: bidirectional data forwarding between two sockets.
 * Supports Minecraft handshake rewriting and MOTD customization.
 */
class TcpRelay(
    private val clientSocket: Socket,
    private val targetAddress: String,
    private val targetPort: Int,
    private val minecraftHostnameRewrite: String? = null,
    private val minecraftPortRewrite: Int? = null,
    private val motdDescription: String? = null,
    private val scoreboardLines: List<String>? = null,
    private val logCollector: LogCollector,
    private val connectionId: String,
    private val scope: CoroutineScope
) {
    private val isRunning = AtomicBoolean(true)
    private var targetSocket: Socket? = null

    suspend fun start() {
        withContext(Dispatchers.IO) {
            try {
                targetSocket = Socket()
                targetSocket?.connect(InetSocketAddress(targetAddress, targetPort), 10000)
                targetSocket?.tcpNoDelay = true
                targetSocket?.keepAlive = true

                logCollector.info("Relay",
                    "[$connectionId] Connected to target $targetAddress:$targetPort")

                if (minecraftHostnameRewrite != null) {
                    handleMinecraftRewriting()
                } else {
                    startBidirectionalRelay()
                }
            } catch (e: Exception) {
                logCollector.error("Relay",
                    "[$connectionId] Failed to connect: ${e.message}")
            } finally {
                cleanup()
            }
        }
    }

    private suspend fun handleMinecraftRewriting() {
        try {
            val clientIn = BufferedInputStream(clientSocket.getInputStream())
            val clientOut = BufferedOutputStream(clientSocket.getOutputStream())
            val targetIn = BufferedInputStream(targetSocket!!.getInputStream())
            val targetOut = BufferedOutputStream(targetSocket!!.getOutputStream())

            // Mark the stream so we can sniff without consuming
            if (clientIn.markSupported()) {
                clientIn.mark(512)
            }

            // Try to sniff and rewrite the handshake
            val handshake = MinecraftProtocol.sniffHandshake(clientIn, logCollector)
            if (handshake != null && minecraftHostnameRewrite != null) {
                val success = MinecraftProtocol.rewriteHandshake(
                    clientIn, targetOut,
                    minecraftHostnameRewrite!!,
                    minecraftPortRewrite ?: targetPort,
                    logCollector
                )
                if (success) {
                    logCollector.info("Relay",
                        "[$connectionId] Minecraft handshake rewritten: ${handshake.serverAddress} -> $minecraftHostnameRewrite")
                    if (handshake.nextState == MinecraftProtocol.STATE_STATUS) {
                        // STATUS: intercept the status response to apply custom MOTD
                        handleStatusFlow(clientIn, clientOut, targetIn, targetOut, handshake)
                    } else if (scoreboardLines != null && scoreboardLines.isNotEmpty()) {
                        // LOGIN: inject custom sidebar scoreboard after login success
                        handleLoginWithScoreboard(clientIn, clientOut, targetIn, targetOut, handshake)
                    } else {
                        // LOGIN: plain bidirectional relay
                        startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
                    }
                    return
                }
            }

            // Fallback: just forward raw data
            if (clientIn.markSupported()) {
                try { clientIn.reset() } catch (_: Exception) {}
            }
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)

        } catch (e: Exception) {
            logCollector.error("Relay",
                "[$connectionId] Error in Minecraft handling: ${e.message}")
            startBidirectionalRelay()
        }
    }

    /**
     * Minecraft server-list (status) flow:
     * 1. Client -> Target: Status Request (0x00)  [forward]
     * 2. Target -> Client: Status Response (0x00) [intercept & rewrite MOTD]
     * 3. Remaining traffic (Ping/Pong): plain bidirectional relay
     */
    private suspend fun handleStatusFlow(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        targetIn: java.io.InputStream,
        targetOut: java.io.OutputStream,
        handshake: MinecraftProtocol.HandshakeData
    ) {
        try {
            // Forward the Status Request packet from client to target
            val statusRequest = MinecraftProtocol.readPacket(clientIn)
            if (statusRequest != null) {
                MinecraftProtocol.writeVarInt(targetOut, statusRequest.size)
                targetOut.write(statusRequest)
                targetOut.flush()

                logCollector.debug("Relay",
                    "[$connectionId] Status request forwarded, awaiting server response")

                // Intercept and rewrite the Status Response (MOTD)
                val intercepted = MinecraftProtocol.interceptStatusResponse(
                    targetIn, clientOut,
                    motdDescription,
                    connectionId,
                    handshake.serverAddress,
                    handshake.serverPort,
                    logCollector
                )

                if (!intercepted) {
                    logCollector.warn("Relay",
                        "[$connectionId] Could not intercept status response, falling back to raw relay")
                    startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
                    return
                }
            }
            // Continue relaying remaining traffic (Ping/Pong, etc.)
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] Status flow error: ${e.message}")
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        }
    }

    /**
     * Minecraft login flow with sidebar scoreboard injection:
     * 1. Forward all login packets from server until Login Success (0x02) is seen
     * 2. Forward one client packet (Login Acknowledged or first play packet)
     * 3. Inject the custom sidebar scoreboard
     * 4. Continue plain bidirectional relay
     */
    private suspend fun handleLoginWithScoreboard(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        targetIn: java.io.InputStream,
        targetOut: java.io.OutputStream,
        handshake: MinecraftProtocol.HandshakeData
    ) {
        try {
            // Give the client socket a short read timeout so we don't block forever
            val originalTimeout = clientSocket.soTimeout
            clientSocket.soTimeout = 3000

            // Read server -> client packets until Login Success (0x02)
            var loginSuccessSeen = false
            var readCount = 0
            while (!loginSuccessSeen && readCount < 30) {
                val packet = MinecraftProtocol.readPacket(targetIn) ?: break
                readCount++

                // Forward packet to client
                MinecraftProtocol.writeVarInt(clientOut, packet.size)
                clientOut.write(packet)
                clientOut.flush()

                // Detect Login Success (packet id 0x02 in login state)
                if (packet.isNotEmpty() && packet[0].toInt() and 0xFF == 0x02) {
                    loginSuccessSeen = true
                    logCollector.info("Relay",
                        "[$connectionId] Login success detected, injecting scoreboard")
                }
            }

            if (loginSuccessSeen) {
                // Forward one client packet (Login Acknowledged for 1.20.2+, or first play packet)
                val clientPacket = MinecraftProtocol.readPacket(clientIn)
                if (clientPacket != null) {
                    MinecraftProtocol.writeVarInt(targetOut, clientPacket.size)
                    targetOut.write(clientPacket)
                    targetOut.flush()
                }

                // Inject the sidebar scoreboard
                injectScoreboard(clientOut, handshake.protocolVersion)
            }

            // Restore timeout and continue relay
            clientSocket.soTimeout = originalTimeout
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] Login scoreboard flow error: ${e.message}")
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        }
    }

    /**
     * Build and send the sidebar scoreboard packets to the client.
     * First line is the title, remaining lines are score entries.
     */
    private fun injectScoreboard(clientOut: java.io.OutputStream, protocolVersion: Int) {
        try {
            val lines = scoreboardLines ?: emptyList()
            if (lines.isEmpty()) return

            val title = lines.first().take(32)
            val body = lines.drop(1).ifEmpty { listOf(" ") }

            val packets = ScoreboardProtocol.buildScoreboardPackets(protocolVersion, title, body)
            for (packet in packets) {
                MinecraftProtocol.writeVarInt(clientOut, packet.size)
                clientOut.write(packet)
            }
            clientOut.flush()

            logCollector.info("Relay",
                "[$connectionId] Scoreboard injected: title='$title', lines=${body.size}")
        } catch (e: Exception) {
            logCollector.warn("Relay",
                "[$connectionId] Scoreboard injection failed: ${e.message}")
        }
    }

    private suspend fun startBidirectionalRelay(
        clientIn: java.io.InputStream? = null,
        clientOut: java.io.OutputStream? = null,
        targetIn: java.io.InputStream? = null,
        targetOut: java.io.OutputStream? = null
    ) {
        val cIn = clientIn ?: clientSocket.getInputStream()
        val cOut = clientOut ?: clientSocket.getOutputStream()
        val tIn = targetIn ?: targetSocket!!.getInputStream()
        val tOut = targetOut ?: targetSocket!!.getOutputStream()

        coroutineScope {
            launch(Dispatchers.IO) {
                relay(cIn, tOut, "C->T")
            }
            launch(Dispatchers.IO) {
                relay(tIn, cOut, "T->C")
            }
        }
    }

    private suspend fun relay(input: java.io.InputStream, output: java.io.OutputStream, direction: String) {
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        try {
            while (isRunning.get()) {
                val n = withContext(Dispatchers.IO) {
                    input.read(buffer)
                }
                if (n == -1) break
                withContext(Dispatchers.IO) {
                    output.write(buffer, 0, n)
                    output.flush()
                }
                totalBytes += n
            }
        } catch (e: SocketException) {
            // Connection closed, normal
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] $direction relay error: ${e.message}")
        } finally {
            logCollector.debug("Relay",
                "[$connectionId] $direction relay ended, transferred $totalBytes bytes")
        }
    }

    private fun cleanup() {
        isRunning.set(false)
        try { clientSocket.close() } catch (_: Exception) {}
        try { targetSocket?.close() } catch (_: Exception) {}
        logCollector.info("Relay", "[$connectionId] Connection closed")
    }

    fun stop() {
        isRunning.set(false)
        cleanup()
    }
}