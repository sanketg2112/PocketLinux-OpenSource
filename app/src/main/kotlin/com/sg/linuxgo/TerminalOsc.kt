package com.sg.linuxgo
/**
 * Pure OSC (Operating System Command) payload helpers.
 */
object TerminalOsc {
    const val MAX_OSC_LENGTH = 4096

    /**
     * Parse OSC 7 file:// URI into a filesystem path.
     * Examples: file://hostname/home/user/proj  →  /home/user/proj
     *           file:///Users/me/code            →  /Users/me/code
     */
    fun parseOsc7Cwd(uri: String): String? {
        val raw = uri.trim()
        if (raw.isEmpty()) return null
        val path = when {
            raw.startsWith("file://") -> {
                val afterScheme = raw.removePrefix("file://")
                // file://host/path or file:///path
                val slash = afterScheme.indexOf('/')
                if (slash >= 0) afterScheme.substring(slash) else afterScheme
            }
            raw.startsWith("/") || raw.startsWith("~") -> raw
            else -> return null
        }
        // Decode a few common percent-escapes without pulling in full URI machinery.
        val decoded = path
            .replace("%20", " ")
            .replace("%2F", "/")
            .replace("%7E", "~", ignoreCase = true)
        return decoded.trim().takeIf { it.isNotEmpty() }?.take(512)
    }

    /**
     * Split an OSC payload into (Ps, Pt). No semicolon → entire string is Ps, Pt empty.
     */
    fun splitPayload(payload: String): Pair<String, String> {
        if (payload.isEmpty()) return "" to ""
        val semi = payload.indexOf(';')
        return if (semi >= 0) {
            payload.substring(0, semi) to payload.substring(semi + 1)
        } else {
            payload to ""
        }
    }

    /** First color spec in OSC 10/11/12 data (`#rrggbb`, `rgb:…`, or `?`). */
    fun firstColorSpec(data: String): String = data.trim().substringBefore(';').trim()
}
