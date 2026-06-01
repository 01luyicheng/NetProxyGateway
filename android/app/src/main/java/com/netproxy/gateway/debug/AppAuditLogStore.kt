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

    /**
     * 清空内部的审计日志列表。
     *
     * 以线程安全的方式将对外暴露的 `entries` 重置为一个空列表。
     */
    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    /**
     * 记录一条 INFO 级别的审计日志条目。
     *
     * 提交时会对输入进行规范化：`tag` 会被修剪并在为空时使用 "App" 作为默认值并截断到允许长度，`message` 会移除换行并在超长时截断以适配存储限制。
     *
     * @param tag 日志来源标签，标识产生该日志的模块或组件。
     * @param message 日志内容文本。
     */
    fun info(tag: String, message: String) {
        append(AuditLogLevel.INFO, tag, message)
    }

    /**
     * 以 WARN 级别将一条审计日志追加到内存日志存储。
     *
     * @param tag 日志标签，会被 trim；若为空则使用 `"App"` 作为默认值，并截断为最多 48 个字符。
     * @param message 日志内容，其中的换行和回车会被替换为空格；若长度超过 240 个字符会被截断并追加 `"..."`。
     */
    fun warn(tag: String, message: String) {
        append(AuditLogLevel.WARN, tag, message)
    }

    /**
     * 记录一条错误级别的审计日志条目。
     *
     * @param tag 用于标识日志来源的标签（例如类名或模块名）。
     * @param message 要记录的日志内容文本。
     */
    fun error(tag: String, message: String) {
        append(AuditLogLevel.ERROR, tag, message)
    }

    /**
     * 将一条经清理的审计日志条目追加到内部存储并保证线程安全与最大条目数限制。
     *
     * 该方法会：
     * - 对 `tag` 做 trim，空字符串替换为 `"App"`，并截断到 48 个字符；
     * - 对 `message` 进行换行替换与长度截断（超出时追加 `"..."`）；
     * - 在同步块内为条目分配自增 id、记录当前时间戳并将条目追加到内部列表，最后保留最近的 `MAX_ENTRIES` 条。
     *
     * @param level 日志等级
     * @param tag 日志标签（将被 trim、默认为 `"App"` 并截断到 48 字符）
     * @param message 日志消息（将去除换行并按 `MAX_MESSAGE_LENGTH` 截断）
     */
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

    /**
     * 清理日志消息中的换行并在超过最大长度时截断。
     *
     * @param raw 原始消息文本。
     * @return 将所有换行符和回车替换为单个空格后的字符串；若其长度超过 `MAX_MESSAGE_LENGTH`，则保留前 `MAX_MESSAGE_LENGTH` 个字符并在末尾追加 `"..."`。 
     */
    private fun sanitizeMessage(raw: String): String {
        val noLineBreaks = raw.replace('\n', ' ').replace('\r', ' ')
        return if (noLineBreaks.length <= MAX_MESSAGE_LENGTH) {
            noLineBreaks
        } else {
            noLineBreaks.take(MAX_MESSAGE_LENGTH) + "..."
        }
    }
}
