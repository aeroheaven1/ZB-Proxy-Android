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
 * Supports Minecraft handshake rewriting on the first packet.
 */
class TcpRelay(
    private val clientSocket: Socket,
    private val targetAddress: String,
    private val targetPort: Int,
    private val minecraftHostnameRewrite: String? = null,
    private val minecraftPortRewrite: Int? = null,
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

    private fun handleMinecraftRewriting() {
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
                    startBidirectionalRelay(clientIn, clientOut, targetIn, targetOut)
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

    private fun startBidirectionalRelay(
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