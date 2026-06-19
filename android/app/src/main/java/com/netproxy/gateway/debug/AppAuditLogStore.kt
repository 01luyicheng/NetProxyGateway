package com.netproxy.gateway.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuditLogLevel {
    INFO,
    WARN,
    ERROR
}

data class AuditLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: AuditLogLevel,
    val tag: String,
    val message: String
)

object AppAuditLogStore {
    private const val MAX_ENTRIES = 300
    private const val MAX_MESSAGE_LENGTH = 240

    private val lock = Any()
    private var nextEntryId = 0L
    private val _entries = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val entries: StateFlow<List<AuditLogEntry>> = _entries.asStateFlow()

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    fun info(tag: String, message: String) {
        append(AuditLogLevel.INFO, tag, message)
    }

    fun warn(tag: String, message: String) {
        append(AuditLogLevel.WARN, tag, message)
    }

    fun error(tag: String, message: String) {
        append(AuditLogLevel.ERROR, tag, message)
    }

    private fun append(level: AuditLogLevel, tag: String, message: String) {
        val sanitizedTag = tag.trim().ifEmpty { "App" }.take(48)
        val sanitizedMessage = sanitizeMessage(message)

        synchronized(lock) {
            nextEntryId += 1
            val entry = AuditLogEntry(
                id = nextEntryId,
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = sanitizedTag,
                message = sanitizedMessage
            )
            val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = next
        }
    }

    private fun sanitizeMessage(raw: String): String {
        val noLineBreaks = raw.replace('\n', ' ').replace('\r', ' ')
        return if (noLineBreaks.length <= MAX_MESSAGE_LENGTH) {
            noLineBreaks
        } else {
            noLineBreaks.take(MAX_MESSAGE_LENGTH) + "..."
        }
    }
}
