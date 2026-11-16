package com.gamefibre

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AppLogs {
    private const val MAX_ENTRIES = 300
    private const val LOG_FILE_NAME = "app-logs.txt"

    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private var logFile: File? = null

    private val bearerRegex = Regex("(?i)(Bearer|Basic|OAuth|ubi_2fa_v1|ubi_v1)\\s+(?:t=)?[A-Za-z0-9._~+/=-]+")
    private val jwtRegex = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]+)?\\b")
    private val querySecretRegex = Regex(
        "(?i)([?&;\\s]|^)(api_?key|key|token|access_token|refresh_token|id_token|code|password|passwd|secret|npsso|remid|ticket|remember_me_ticket|twoFactorAuthenticationTicket|cookie|authorization|challenge_url|steam_guard|client_secret)=([^&\\s,;\"'\\]\\}]+)"
    )
    private val jsonSecretRegex = Regex(
        "(?i)(\"(?:api_?key|key|token|access_token|refresh_token|id_token|code|password|passwd|secret|npsso|remid|ticket|remember_me_ticket|twoFactorAuthenticationTicket|cookie|authorization|challenge_url|client_secret)\"\\s*:\\s*\")([^\"]*)(\")"
    )
    private val urlCredentialsRegex = Regex("(?i)(https?://)([^/@\\s:]+):([^/@\\s]+)@")
    private val qrUrlRegex = Regex("(?i)https?://(?:s\\.team/q/|steamcommunity\\.com/mobilelogin)[^\\s\"']*")

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val file = File(context.filesDir, LOG_FILE_NAME)
            logFile = file
            if (file.exists()) {
                val existing = runCatching { file.readLines() }.getOrDefault(emptyList())
                val merged = (existing + entries).takeLast(MAX_ENTRIES)
                entries.clear()
                entries.addAll(merged)
                persistLocked()
            } else if (entries.isNotEmpty()) {
                persistLocked()
            }
        }
    }

    fun log(tag: String, message: String) {
        append(formatEntry("I", tag, message))
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) {
            message
        } else {
            val type = throwable::class.java.simpleName.ifBlank { "Exception" }
            val errMsg = throwable.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            val cause = throwable.cause?.let { c ->
                val cType = c::class.java.simpleName.ifBlank { "Cause" }
                val cMsg = c.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                " (caused by $cType$cMsg)"
            }.orEmpty()
            "$message [$type$errMsg$cause]"
        }
        append(formatEntry("E", tag, detail))
    }

    fun read(): String {
        synchronized(lock) {
            return entries.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { logFile?.delete() }
        }
    }

    internal fun sanitize(raw: String): String {
        var result = raw.replace('\r', ' ').replace('\n', ' ').trim()
        result = urlCredentialsRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]@"
        }
        result = qrUrlRegex.replace(result, "[REDACTED_URL]")
        result = bearerRegex.replace(result) { match ->
            "${match.groupValues[1]} [REDACTED]"
        }
        result = querySecretRegex.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}=[REDACTED]"
        }
        result = jsonSecretRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}"
        }
        result = jwtRegex.replace(result, "[REDACTED_TOKEN]")
        return result
    }

    private fun formatEntry(level: String, tag: String, message: String): String {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        return "$timestamp $level/$tag: ${sanitize(message)}"
    }

    private fun append(line: String) {
        synchronized(lock) {
            while (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
            persistLocked()
        }
    }

    private fun persistLocked() {
        val file = logFile ?: return
        runCatching {
            file.writeText(entries.joinToString("\n"))
        }
    }
}
