package com.chromia.lspmcp

import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.serialization.json.JsonPrimitive

/**
 * Level-filtered logging for the server.
 *
 * Every message goes to stderr, because stdout carries the MCP JSON-RPC stream and any stray
 * text on it corrupts the protocol. Messages at or above the current level are additionally
 * forwarded to the client as `notifications/message`, once a session has called [connect].
 *
 * The initial level comes from `LOG_LEVEL`; clients change it at runtime through the
 * `logging/setLevel` request or the `set_log_level` tool.
 */
object Log {
    private const val LOGGER_NAME = "chromia-lsp-mcp"

    @Volatile
    private var sink: ((LoggingMessageNotification) -> Unit)? = null

    @Volatile
    var level: LoggingLevel = parseLevel(System.getenv("LOG_LEVEL")) ?: LoggingLevel.Info
        private set

    /** Routes notifications for messages at or above [level] to [sink]. */
    fun connect(sink: (LoggingMessageNotification) -> Unit) {
        this.sink = sink
    }

    fun setLevel(level: LoggingLevel) {
        val previous = this.level
        this.level = level
        // Announced unconditionally: raising the level should still confirm it took effect.
        writeToStderr(LoggingLevel.Notice, "Log level changed from $previous to $level")
    }

    fun parseLevel(name: String?): LoggingLevel? =
        LoggingLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun debug(message: () -> String): Unit = log(LoggingLevel.Debug, message)

    fun info(message: () -> String): Unit = log(LoggingLevel.Info, message)

    fun notice(message: () -> String): Unit = log(LoggingLevel.Notice, message)

    fun warning(message: () -> String): Unit = log(LoggingLevel.Warning, message)

    fun error(message: () -> String): Unit = log(LoggingLevel.Error, message)

    fun log(level: LoggingLevel, message: () -> String) {
        if (level < this.level) return
        val text = message()
        writeToStderr(level, text)
        sink?.invoke(
            LoggingMessageNotification(
                LoggingMessageNotificationParams(
                    level = level,
                    data = JsonPrimitive(text),
                    logger = LOGGER_NAME,
                ),
            ),
        )
    }

    private fun writeToStderr(level: LoggingLevel, text: String) {
        System.err.println("[${level.name.uppercase()}] $text")
    }
}
