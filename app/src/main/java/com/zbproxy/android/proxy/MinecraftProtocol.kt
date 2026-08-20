package com.zbproxy.android.proxy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zbproxy.android.util.LogCollector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Minecraft protocol utilities for handshake sniffing and hostname rewriting.
 * Implements the Minecraft Java Edition protocol.
 */
object MinecraftProtocol {

    private const val PACKET_HANDSHAKE = 0x00
    const val STATE_STATUS = 1
    const val STATE_LOGIN = 2

    // Status packets
    private const val STATUS_RESPONSE = 0x00
    private const val PING_PONG = 0x01

    data class HandshakeData(
        val protocolVersion: Int,
        val serverAddress: String,
        val serverPort: Int,
        val nextState: Int
    )

    /**
     * Read a VarInt from the input stream.
     */
    fun readVarInt(input: InputStream): Int {
        var value = 0
        var position = 0
        var currentByte: Int

        do {
            currentByte = input.read()
            if (currentByte == -1) throw java.io.EOFException("Unexpected end of stream")
            value = value or ((currentByte and 0x7F) shl position)
            position += 7
            if (position > 32) throw RuntimeException("VarInt too big")
        } while ((currentByte and 0x80) != 0)

        return value
    }

    /**
     * Write a VarInt to the output stream.
     */
    fun writeVarInt(output: OutputStream, value: Int) {
        var v = value
        do {
            var temp = (v and 0x7F)
            v = v ushr 7
            if (v != 0) {
                temp = temp or 0x80
            }
            output.write(temp)
        } while (v != 0)
    }

    /**
     * Write a string as VarInt-prefixed UTF-8.
     */
    fun writeString(output: OutputStream, str: String) {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(output, bytes.size)
        output.write(bytes)
    }

    /**
     * Read a VarInt-prefixed string.
     */
    fun readString(input: InputStream): String {
        val length = readVarInt(input)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = input.read(bytes, read, length - read)
            if (r == -1) throw java.io.EOFException("Unexpected end of stream")
            read += r
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Sniff the Minecraft client handshake packet.
     * Returns the handshake data without consuming the packet from the stream.
     * Uses mark/reset to preserve the stream position.
     */
    fun sniffHandshake(input: InputStream, logCollector: LogCollector): HandshakeData? {
        return try {
            if (!input.markSupported()) return null
            input.mark(512)
            val dis = DataInputStream(input)

            // Read packet length
            val packetLength = readVarInt(dis)
            if (packetLength <= 0) {
                input.reset()
                return null
            }

            // Read packet ID
            val packetId = readVarInt(dis)
            if (packetId != PACKET_HANDSHAKE) {
                input.reset()
                return null
            }

            // Read handshake fields
            val protocolVersion = readVarInt(dis)
            val serverAddress = readString(dis)
            val serverPort = dis.readShort().toInt() and 0xFFFF
            val nextState = readVarInt(dis)

            input.reset()

            HandshakeData(protocolVersion, serverAddress, serverPort, nextState).also {
                logCollector.debug("Minecraft",
                    "Sniffed handshake: protocol=$protocolVersion, address=$serverAddress:$serverPort, nextState=$nextState")
            }
        } catch (e: Exception) {
            try { input.reset() } catch (_: Exception) {}
            logCollector.debug("Minecraft", "Failed to sniff handshake: ${e.message}")
            null
        }
    }

    /**
     * Rewrite the hostname in a Minecraft handshake packet.
     * Reads the original packet, modifies the hostname, and writes the new packet.
     */
    fun rewriteHandshake(
        input: InputStream,
        output: OutputStream,
        newHostname: String,
        newPort: Int,
        logCollector: LogCollector
    ): Boolean {
        return try {
            val dis = DataInputStream(input)

            // Read original packet
            val packetLength = readVarInt(dis)
            val packetId = readVarInt(dis)
            val protocolVersion = readVarInt(dis)
            val originalAddress = readString(dis)
            val originalPort = dis.readShort().toInt() and 0xFFFF
            val nextState = readVarInt(dis)

            logCollector.info("Minecraft",
                "Rewriting handshake: $originalAddress:$originalPort -> $newHostname:$newPort")

            // Build new packet
            val newHostnameBytes = newHostname.toByteArray(StandardCharsets.UTF_8)
            val newPacketLength = 1 + // packet ID
                    varIntLength(protocolVersion) +
                    varIntLength(newHostnameBytes.size) + newHostnameBytes.size +
                    2 + // port (short)
                    varIntLength(nextState)

            val buffer = ByteBuffer.allocate(varIntLength(newPacketLength) + newPacketLength)
            writeVarIntToBuffer(buffer, newPacketLength)
            writeVarIntToBuffer(buffer, packetId)
            writeVarIntToBuffer(buffer, protocolVersion)
            writeVarIntToBuffer(buffer, newHostnameBytes.size)
            buffer.put(newHostnameBytes)
            buffer.putShort(newPort.toShort())
            writeVarIntToBuffer(buffer, nextState)

            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)

            true
        } catch (e: Exception) {
            logCollector.error("Minecraft", "Failed to rewrite handshake: ${e.message}")
            false
        }
    }

    private fun varIntLength(value: Int): Int {
        var v = value
        var length = 0
        do {
            length++
            v = v ushr 7
        } while (v != 0)
        return length
    }

    private fun writeVarIntToBuffer(buffer: ByteBuffer, value: Int) {
        var v = value
        do {
            var temp = (v and 0x7F)
            v = v ushr 7
            if (v != 0) {
                temp = temp or 0x80
            }
            buffer.put(temp.toByte())
        } while (v != 0)
    }

    /**
     * Read a complete Minecraft packet (length-prefixed).
     * Returns the raw packet payload (without length prefix), or null on EOF.
     */
    fun readPacket(input: InputStream): ByteArray? {
        return try {
            val length = readVarInt(input)
            if (length <= 0 || length > 32767) return null
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val r = input.read(data, offset, length - offset)
                if (r == -1) return null
                offset += r
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Modify the MOTD description inside a Minecraft status response JSON.
     * Supports § color codes, \n line breaks, and {NAME}/{HOST}/{PORT}/{INFO} placeholders.
     */
    fun modifyStatusJson(
        json: String,
        motdDescription: String?,
        serviceName: String?,
        hostname: String?,
        port: Int?
    ): String {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (motdDescription != null && motdDescription.isNotBlank()) {
                var text = motdDescription
                text = text.replace("{NAME}", serviceName ?: "ZBProxy")
                text = text.replace("{HOST}", hostname ?: "localhost")
                text = text.replace("{PORT}", (port ?: 25565).toString())
                text = text.replace("{INFO}", "ZBProxy Android")

                val desc = JsonObject()
                desc.addProperty("text", text)
                root.add("description", desc)
            }
            root.toString()
        } catch (e: Exception) {
            // If parsing fails, return original JSON untouched
            json
        }
    }

    /**
     * Intercept a Minecraft Status Response packet from the target server,
     * rewrite the MOTD description, and write the modified packet to the client.
     * Returns true if a status response was successfully rewritten.
     */
    fun interceptStatusResponse(
        targetIn: InputStream,
        clientOut: OutputStream,
        motdDescription: String?,
        serviceName: String?,
        hostname: String?,
        port: Int?,
        logCollector: LogCollector
    ): Boolean {
        return try {
            val packetLen = readVarInt(targetIn)
            val packetData = ByteArray(packetLen)
            var offset = 0
            while (offset < packetLen) {
                val r = targetIn.read(packetData, offset, packetLen - offset)
                if (r == -1) return false
                offset += r
            }

            val packetStream = ByteArrayInputStream(packetData)
            val packetId = readVarInt(packetStream)

            if (packetId != STATUS_RESPONSE) {
                // Not a status response - forward as-is
                writeVarInt(clientOut, packetLen)
                clientOut.write(packetData)
                clientOut.flush()
                return true
            }

            // Read the status JSON
            val jsonLen = readVarInt(packetStream)
            val jsonBytes = ByteArray(jsonLen)
            var read = 0
            while (read < jsonLen) {
                val r = packetStream.read(jsonBytes, read, jsonLen - read)
                if (r == -1) break
                read += r
            }
            val originalJson = String(jsonBytes, StandardCharsets.UTF_8)
            val newJson = modifyStatusJson(originalJson, motdDescription, serviceName, hostname, port)

            logCollector.info("Minecraft", "Status response intercepted, MOTD applied")

            // Rebuild the packet
            val newJsonBytes = newJson.toByteArray(StandardCharsets.UTF_8)
            val payload = ByteArrayOutputStream()
            writeVarInt(payload, STATUS_RESPONSE)
            writeVarInt(payload, newJsonBytes.size)
            payload.write(newJsonBytes)
            val newPayload = payload.toByteArray()

            writeVarInt(clientOut, newPayload.size)
            clientOut.write(newPayload)
            clientOut.flush()

            true
        } catch (e: Exception) {
            logCollector.debug("Minecraft", "Failed to intercept status response: ${e.message}")
            false
        }
    }
}