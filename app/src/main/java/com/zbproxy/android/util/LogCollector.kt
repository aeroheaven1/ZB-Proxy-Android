package com.zbproxy.android.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Level,
    val tag: String,
    val message: String
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedMessage: String
        get() = "[$formattedTime] [${level.name}] [$tag] $message"
}

class LogCollector {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val maxEntries = 5000

    fun debug(tag: String, message: String) = addLog(LogEntry.Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = addLog(LogEntry.Level.INFO, tag, message)
    fun warn(tag: String, message: String) = addLog(LogEntry.Level.WARN, tag, message)
    fun error(tag: String, message: String) = addLog(LogEntry.Level.ERROR, tag, message)

    private fun addLog(level: LogEntry.Level, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > maxEntries) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }
}