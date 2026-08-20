package com.zbproxy.android.proxy

import com.zbproxy.android.util.LogCollector
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP relay: bidirectional data forwarding between two sockets.
 * Supports Minecraft handshake rewriting, MOTD customization,
 * and player name identification from the Login Start packet.
 */
class TcpRelay(
    private val clientSocket: Socket,
    private val targetAddress: String,
    private val targetPort: Int,
    private val minecraftHostnameRewrite: String? = null,
    private val minecraftPortRewrite: Int? = null,
    private val motdDescription: String? = null,
    private val logCollector: LogCollector,
    private val connectionId: String,
    private val scope: CoroutineScope
) {
    private val isRunning = AtomicBoolean(true)
    private var targetSocket: Socket? = null
    private var playerName: String? = null

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
                    } else {
                        // LOGIN: identify the player from the Login Start packet,
                        // then relay normally. Failure to identify never breaks the connection.
                        identifyPlayer(clientIn, targetOut)
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
     * Read the client's Login Start packet (the first packet sent after the
     * handshake in login state) to identify the player name.
     * The packet is always forwarded; parsing failure is non-fatal.
     */
    private fun identifyPlayer(clientIn: java.io.InputStream, targetOut: java.io.OutputStream) {
        try {
            // Short timeout: a normal client sends Login Start immediately.
            val originalTimeout = clientSocket.soTimeout
            clientSocket.soTimeout = 3000
            val loginStart = MinecraftProtocol.readPacket(clientIn)
            clientSocket.soTimeout = originalTimeout

            if (loginStart == null) {
                logCollector.debug("Relay",
                    "[$connectionId] Login Start not received, player name unknown")
                return
            }

            // Forward the packet first (safety first)
            forwardPacket(targetOut, loginStart)

            // Then try to parse the player name (packet id 0x00 + String name)
            val name = parsePlayerName(loginStart)
            if (name != null) {
                playerName = name
                logCollector.info("Minecraft",
                    "[$connectionId] Player joined: $name")
            }
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] Player identification skipped: ${e.message}")
        }
    }

    /**
     * Parse the player name from a Login Start packet payload.
     * Returns null if the packet is not a valid Login Start.
     */
    private fun parsePlayerName(loginStart: ByteArray): String? {
        return try {
            val stream = ByteArrayInputStream(loginStart)
            val packetId = MinecraftProtocol.readVarInt(stream)
            if (packetId != 0x00) return null
            MinecraftProtocol.readString(stream)
        } catch (e: Exception) {
            null
        }
    }

    private fun forwardPacket(output: java.io.OutputStream, packet: ByteArray) {
        MinecraftProtocol.writeVarInt(output, packet.size)
        output.write(packet)
        output.flush()
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
        logCollector.info("Relay", "[$connectionId] Connection closed" +
            (playerName?.let { " (player: $it)" } ?: ""))
    }

    fun stop() {
        isRunning.set(false)
        cleanup()
    }
}