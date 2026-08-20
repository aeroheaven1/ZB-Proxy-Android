package com.zbproxy.android.proxy

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minecraft Scoreboard injection.
 * Constructs Scoreboard Objective / Display / Update Score packets that display
 * custom content in the sidebar (right side of the screen).
 *
 * Supported version groups:
 *  - 1.8.x             (protocol 47)
 *  - 1.9 - 1.12.2      (protocol 107-340)
 *  - 1.13 - 1.20.2     (protocol 393-764)
 *  - 1.20.3 - 1.20.4   (protocol 765)
 *  - 1.20.5 - 1.21.1   (protocol 766-767)  <-- includes 1.21.1
 *  - 1.21.2+           (protocol 768+)      NOT SUPPORTED (scoreboard rewrite)
 */
object ScoreboardProtocol {

    private const val OBJECTIVE_NAME = "zbp_info"
    private const val POSITION_SIDEBAR = 1
    private const val MAX_LINE_LENGTH = 40

    // Packet IDs (clientbound play)
    private const val OBJ_ID_1_8 = 0x3F
    private const val OBJ_ID_1_9_TO_1_12 = 0x3F
    private const val OBJ_ID_1_13_TO_1_20_2 = 0x53
    private const val OBJ_ID_1_20_3_TO_1_20_4 = 0x5E
    private const val OBJ_ID_1_20_5_TO_1_20_6 = 0x5F
    private const val OBJ_ID_1_21 = 0x60

    private const val DISPLAY_ID_1_8 = 0x3D
    private const val DISPLAY_ID_1_9_TO_1_12 = 0x3A
    private const val DISPLAY_ID_1_13_PLUS = 0x4E
    private const val DISPLAY_ID_1_20_3_PLUS = 0x56

    private const val SCORE_ID_1_8 = 0x3C
    private const val SCORE_ID_1_9_TO_1_12 = 0x42
    private const val SCORE_ID_1_13_TO_1_20_2 = 0x49
    private const val SCORE_ID_1_20_3_PLUS = 0x58

    /**
     * Build the full packet sequence to display a sidebar scoreboard.
     * Returns a list of raw packets (each WITHOUT length prefix).
     * Returns an empty list for unsupported protocol versions (1.21.2+).
     */
    fun buildScoreboardPackets(protocolVersion: Int, title: String, lines: List<String>): List<ByteArray> {
        // 1.21.2+ completely rewrote the scoreboard protocol - not supported
        if (protocolVersion > 767) return emptyList()

        val packets = mutableListOf<ByteArray>()

        // 1. Create objective
        packets.add(buildObjectivePacket(protocolVersion, title))
        // 2. Display in sidebar
        packets.add(buildDisplayPacket(protocolVersion))
        // 3. Update scores (one per line)
        var score = lines.size + 1
        for (line in lines) {
            val trimmed = line.take(MAX_LINE_LENGTH)
            packets.add(buildScorePacket(protocolVersion, trimmed, score))
            score--
        }

        return packets
    }

    private fun buildObjectivePacket(protocolVersion: Int, title: String): ByteArray {
        val buffer = ByteArrayOutputStream()

        when {
            protocolVersion <= 47 -> {
                // 1.8: 0x3F, String name + String displayName + Byte mode(0)
                buffer.write(OBJ_ID_1_8)
                writeString(buffer, OBJECTIVE_NAME)
                writeString(buffer, title)
                buffer.write(0) // mode: create
            }
            protocolVersion <= 340 -> {
                // 1.9-1.12.2: 0x3F, String name + String displayName + VarInt mode(0)
                buffer.write(OBJ_ID_1_9_TO_1_12)
                writeString(buffer, OBJECTIVE_NAME)
                writeString(buffer, title)
                writeVarInt(buffer, 0) // mode: create
            }
            protocolVersion <= 764 -> {
                // 1.13-1.20.2: 0x53, String name + VarInt mode(0) + Chat displayName + VarInt type(0)
                buffer.write(OBJ_ID_1_13_TO_1_20_2)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, 0) // mode: create
                writeString(buffer, chatComponent(title))
                writeVarInt(buffer, 0) // type: integer
            }
            protocolVersion == 765 -> {
                // 1.20.3-1.20.4: 0x5E, String name + VarInt mode(0) + Chat displayName + VarInt type(0)
                buffer.write(OBJ_ID_1_20_3_TO_1_20_4)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, 0) // mode: create
                writeString(buffer, chatComponent(title))
                writeVarInt(buffer, 0) // type: integer
            }
            protocolVersion == 766 -> {
                // 1.20.5-1.20.6: 0x5F, String name + VarInt mode(0) + Chat displayName + VarInt type(0) + NumberFormat(absent)
                buffer.write(OBJ_ID_1_20_5_TO_1_20_6)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, 0) // mode: create
                writeString(buffer, chatComponent(title))
                writeVarInt(buffer, 0) // type: integer
                buffer.write(0x00) // Number Format: absent
            }
            else -> {
                // 1.21-1.21.1: 0x60, String name + VarInt mode(0) + Chat displayName + VarInt type(0) + NumberFormat(absent)
                buffer.write(OBJ_ID_1_21)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, 0) // mode: create
                writeString(buffer, chatComponent(title))
                writeVarInt(buffer, 0) // type: integer
                buffer.write(0x00) // Number Format: absent
            }
        }
        return buffer.toByteArray()
    }

    private fun buildDisplayPacket(protocolVersion: Int): ByteArray {
        val buffer = ByteArrayOutputStream()

        when {
            protocolVersion <= 47 -> {
                // 1.8: 0x3D, Byte position + String objectiveName
                buffer.write(DISPLAY_ID_1_8)
                buffer.write(POSITION_SIDEBAR)
                writeString(buffer, OBJECTIVE_NAME)
            }
            protocolVersion <= 340 -> {
                // 1.9-1.12.2: 0x3A, Byte position + String objectiveName
                buffer.write(DISPLAY_ID_1_9_TO_1_12)
                buffer.write(POSITION_SIDEBAR)
                writeString(buffer, OBJECTIVE_NAME)
            }
            protocolVersion <= 764 -> {
                // 1.13-1.20.2: 0x4E, VarInt position + String objectiveName
                buffer.write(DISPLAY_ID_1_13_PLUS)
                writeVarInt(buffer, POSITION_SIDEBAR)
                writeString(buffer, OBJECTIVE_NAME)
            }
            else -> {
                // 1.20.3-1.21.1: 0x56, VarInt position + String objectiveName
                buffer.write(DISPLAY_ID_1_20_3_PLUS)
                writeVarInt(buffer, POSITION_SIDEBAR)
                writeString(buffer, OBJECTIVE_NAME)
            }
        }
        return buffer.toByteArray()
    }

    private fun buildScorePacket(protocolVersion: Int, playerName: String, value: Int): ByteArray {
        val buffer = ByteArrayOutputStream()

        when {
            protocolVersion <= 47 -> {
                // 1.8: 0x3C, String objectiveName + String playerName + Int value + Byte update(0)
                buffer.write(SCORE_ID_1_8)
                writeString(buffer, OBJECTIVE_NAME)
                writeString(buffer, playerName)
                writeInt(buffer, value)
                buffer.write(0) // update: add
            }
            protocolVersion <= 340 -> {
                // 1.9-1.12.2: 0x42, String playerName + VarInt action(0) + String objectiveName + VarInt value
                buffer.write(SCORE_ID_1_9_TO_1_12)
                writeString(buffer, playerName)
                writeVarInt(buffer, 0) // action: create/update
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, value)
            }
            protocolVersion <= 764 -> {
                // 1.13-1.20.2: 0x49, String playerName + VarInt action(0) + String objectiveName + VarInt value
                buffer.write(SCORE_ID_1_13_TO_1_20_2)
                writeString(buffer, playerName)
                writeVarInt(buffer, 0) // action: create/update
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, value)
            }
            protocolVersion <= 765 -> {
                // 1.20.3-1.20.4: 0x58, String entityName + String objectiveName + VarInt score
                buffer.write(SCORE_ID_1_20_3_PLUS)
                writeString(buffer, playerName)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, value)
            }
            else -> {
                // 1.20.5-1.21.1: 0x58, String entityName + String objectiveName + VarInt score + NumberFormat(absent)
                buffer.write(SCORE_ID_1_20_3_PLUS)
                writeString(buffer, playerName)
                writeString(buffer, OBJECTIVE_NAME)
                writeVarInt(buffer, value)
                buffer.write(0x00) // Number Format: absent
            }
        }
        return buffer.toByteArray()
    }

    private fun chatComponent(text: String): String {
        return """{"text":${jsonEscape(text)}}"""
    }

    private fun jsonEscape(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun writeString(buffer: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(buffer, bytes.size)
        buffer.write(bytes, 0, bytes.size)
    }

    private fun writeVarInt(buffer: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var temp = (v and 0x7F)
            v = v ushr 7
            if (v != 0) temp = temp or 0x80
            buffer.write(temp)
        } while (v != 0)
    }

    private fun writeInt(buffer: ByteArrayOutputStream, value: Int) {
        buffer.write((value ushr 24) and 0xFF)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
    }
}