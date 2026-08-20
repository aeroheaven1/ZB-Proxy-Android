package com.zbproxy.android.proxy

import com.zbproxy.android.util.LogCollector
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
     * Minecraft login flow with sidebar scoreboard injection.
     *
     * Safety-first design:
     *  - If the server uses encryption (Online Mode) or packet compression,
     *    scoreboard injection is skipped and the connection relays normally
     *    so the player can always join.
     *  - For 1.20.2+ (protocol >= 764) the client enters a Configuration phase
     *    after Login Success. Configuration is bidirectional, so we keep
     *    forwarding both directions concurrently and detect the clientbound
     *    Finish Configuration packet (0x02 for 1.20.2-1.20.4, 0x03 for 1.20.5+)
     *    in the server->client stream, then inject.
     *  - For older versions, inject right after Login Success + first client packet.
     */
    private suspend fun handleLoginWithScoreboard(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        targetIn: java.io.InputStream,
        targetOut: java.io.OutputStream,
        handshake: MinecraftProtocol.HandshakeData
    ) {
        val originalTimeout = clientSocket.soTimeout
        try {
            // Give the client socket a short read timeout so we don't block forever
            clientSocket.soTimeout = 5000

            // 1. Read server -> client packets until Login Success (0x02).
            //    Watch for Encryption Request (0x01) and Set Compression (0x03).
            var loginSuccessSeen = false
            var encrypted = false
            var compressed = false
            var readCount = 0
            while (!loginSuccessSeen && readCount < 30) {
                val packet = MinecraftProtocol.readPacket(targetIn) ?: break
                readCount++
                forwardPacket(clientOut, packet)

                if (packet.isNotEmpty()) {
                    when (packet[0].toInt() and 0xFF) {
                        0x01 -> { // Encryption Request (login) - cannot decrypt
                            encrypted = true
                            logCollector.warn("Relay",
                                "[$connectionId] Server uses encryption, scoreboard injection disabled")
                        }
                        0x03 -> { // Set Compression (login)
                            val threshold = parseVarInt(packet, 1)
                            if (threshold != null && threshold > 0) {
                                compressed = true
                                logCollector.warn("Relay",
                                    "[$connectionId] Server uses compression, scoreboard injection disabled")
                            }
                        }
                        0x02 -> { // Login Success
                            loginSuccessSeen = true
                            logCollector.info("Relay",
                                "[$connectionId] Login success detected (protocol ${handshake.protocolVersion})")
                        }
                    }
                }
            }

            if (!encrypted && !compressed && loginSuccessSeen) {
                if (handshake.protocolVersion >= 764) {
                    // Restore timeout before long-lived relay so idle connections don't drop
                    clientSocket.soTimeout = originalTimeout
                    // 1.20.2+: concurrent bidirectional forwarding with config-finish detection
                    // Blocks until the connection closes, so return afterwards.
                    relayWithConfigDetection(clientIn, clientOut, targetIn, targetOut, handshake.protocolVersion)
                    return
                } else {
                    // Older versions: forward one client packet, then inject.
                    // Keep the short timeout; if no packet arrives, skip injection safely.
                    val clientPacket = MinecraftProtocol.readPacket(clientIn)
                    if (clientPacket != null) forwardPacket(targetOut, clientPacket)
                    injectScoreboard(clientOut, handshake.protocolVersion)
                }
            } else if (!loginSuccessSeen) {
                logCollector.warn("Relay",
                    "[$connectionId] Login success not detected, scoreboard skipped")
            }

            // Restore timeout (in case we skipped the branch above) and continue relay
            clientSocket.soTimeout = originalTimeout
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] Login scoreboard flow error: ${e.message}")
            clientSocket.soTimeout = originalTimeout
            startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
        }
    }

    /**
     * For 1.20.2+: forward both directions concurrently.
     * The server->client direction parses packets and injects the scoreboard
     * once the clientbound Finish Configuration packet is seen.
     */
    private suspend fun relayWithConfigDetection(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        targetIn: java.io.InputStream,
        targetOut: java.io.OutputStream,
        protocolVersion: Int
    ) {
        // Finish Configuration packet id in configuration state:
        // 1.20.2-1.20.4 = 0x02, 1.20.5+ = 0x03
        val finishConfigId = if (protocolVersion >= 766) 0x03 else 0x02
        logCollector.debug("Relay",
            "[$connectionId] Configuration phase active, waiting for Finish Configuration (0x${finishConfigId.toString(16)})")

        coroutineScope {
            launch(Dispatchers.IO) {
                relay(clientIn, targetOut, "C->T")
            }
            launch(Dispatchers.IO) {
                relayTtoCWithInjection(targetIn, clientOut, finishConfigId, protocolVersion)
            }
        }
    }

    /**
     * Server->client relay that parses individual packets so we can detect
     * the Finish Configuration packet and inject the scoreboard at the right
     * moment, without blocking the client->server direction.
     */
    private suspend fun relayTtoCWithInjection(
        targetIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        finishConfigId: Int,
        protocolVersion: Int
    ) {
        val temp = ByteArray(8192)
        val buffer = ByteArrayOutputStream()
        var injected = false
        var totalBytes = 0L
        try {
            while (isRunning.get()) {
                val n = withContext(Dispatchers.IO) {
                    targetIn.read(temp)
                }
                if (n == -1) break
                buffer.write(temp, 0, n)
                totalBytes += n

                val bytes = buffer.toByteArray()
                var consumed = 0
                while (consumed < bytes.size) {
                    val packetInfo = tryReadPacket(bytes, consumed) ?: break
                    // Forward this packet
                    withContext(Dispatchers.IO) {
                        clientOut.write(bytes, consumed, packetInfo.varintLen + packetInfo.packetLen)
                        clientOut.flush()
                    }
                    // Inject after Finish Configuration
                    if (!injected && packetInfo.packet.isNotEmpty() &&
                        (packetInfo.packet[0].toInt() and 0xFF) == finishConfigId
                    ) {
                        injected = true
                        logCollector.info("Relay",
                            "[$connectionId] Configuration finished, injecting scoreboard")
                        injectScoreboard(clientOut, protocolVersion)
                        // Flush remaining buffered bytes, then switch to raw relay
                        if (consumed + packetInfo.varintLen + packetInfo.packetLen < bytes.size) {
                            val rest = bytes.copyOfRange(
                                consumed + packetInfo.varintLen + packetInfo.packetLen, bytes.size)
                            withContext(Dispatchers.IO) {
                                clientOut.write(rest)
                                clientOut.flush()
                            }
                        }
                        relay(targetIn, clientOut, "T->C")
                        return
                    }
                    consumed += packetInfo.varintLen + packetInfo.packetLen
                }

                // Drop consumed bytes from buffer
                if (consumed > 0) {
                    val remaining = bytes.copyOfRange(consumed, bytes.size)
                    buffer.reset()
                    if (remaining.isNotEmpty()) buffer.write(remaining)
                }
            }
        } catch (e: SocketException) {
            // Connection closed, normal
        } catch (e: Exception) {
            logCollector.debug("Relay",
                "[$connectionId] T->C relay error: ${e.message}")
        } finally {
            logCollector.debug("Relay",
                "[$connectionId] T->C relay ended, transferred $totalBytes bytes")
        }
    }

    /**
     * Try to parse one complete Minecraft packet (VarInt length + payload)
     * from a byte array starting at [offset]. Returns null if incomplete.
     */
    private fun tryReadPacket(data: ByteArray, offset: Int): PacketInfo? {
        // Read VarInt length
        var value = 0
        var shift = 0
        var current = offset
        var varintLen = 0
        while (true) {
            if (current >= data.size) return null // incomplete varint
            val b = data[current].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            current++
            varintLen++
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 35) return null
        }
        if (value <= 0 || value > 32767) return null
        if (offset + varintLen + value > data.size) return null // incomplete payload

        val packet = data.copyOfRange(offset + varintLen, offset + varintLen + value)
        return PacketInfo(varintLen, value, packet)
    }

    private data class PacketInfo(val varintLen: Int, val packetLen: Int, val packet: ByteArray)

    /**
     * Parse a VarInt from a packet payload at the given offset.
     * Returns null if parsing fails.
     */
    private fun parseVarInt(data: ByteArray, offset: Int): Int? {
        if (offset >= data.size) return null
        var value = 0
        var shift = 0
        var current = offset
        while (true) {
            if (current >= data.size) return null
            val b = data[current].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            current++
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 35) return null
        }
        return value
    }

    private fun forwardPacket(output: java.io.OutputStream, packet: ByteArray) {
        MinecraftProtocol.writeVarInt(output, packet.size)
        output.write(packet)
        output.flush()
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