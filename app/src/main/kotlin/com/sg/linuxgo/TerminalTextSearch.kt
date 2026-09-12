package com.sg.linuxgo
/**
 * Find-in-scrollback helpers, token extraction, and URL detection for terminal text.
 */
object TerminalTextSearch {

    data class Match(
        val start: Int,
        val end: Int,
        val lineIndex: Int
    )

    /**
     * Result of a long-press hit-test on a terminal line.
     * [token] is the whitespace-delimited run under the column (path, flag, URL, etc.).
     * [url] is set when a URL covers that column (token itself or a larger match on the line).
     * [urlStart]/[urlEnd] is the half-open column range of that URL on the line when present.
     */
    data class CellHit(
        val lineText: String,
        val column: Int,
        val token: String,
        val tokenStart: Int,
        val tokenEnd: Int,
        val url: String?,
        val urlStart: Int = 0,
        val urlEnd: Int = 0
    )

    /** Normalized URL plus its half-open column span on the source line. */
    data class UrlSpan(
        val url: String,
        val start: Int,
        val endExclusive: Int
    )

    /**
     * Returns all case-insensitive matches of [query] in [text], with optional line index map.
     */
    fun findAll(text: String, query: String): List<Match> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val hay = text.lowercase()
        val needle = query.lowercase()
        val results = mutableListOf<Match>()
        var from = 0
        while (from <= hay.length - needle.length) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            val lineIndex = text.substring(0, idx).count { it == '\n' }
            results += Match(start = idx, end = idx + needle.length, lineIndex = lineIndex)
            from = idx + needle.length.coerceAtLeast(1)
        }
        return results
    }

    fun extractSelection(text: String, start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        if (a >= b) return ""
        return text.substring(a, b)
    }

    /**
     * URL-like spans: `http(s)://…`, `ftp://…`, and bare `www.…`.
     * Trailing sentence punctuation is stripped from matches.
     */
    private val URL_REGEX = Regex(
        """(?i)\b((?:https?|ftp)://[^\s<>"'`)\]}]+|www\.[^\s<>"'`)\]}]+)"""
    )

    private val TRAILING_URL_PUNCT = Regex("""[.,;:!?)]+$""")

    /**
     * Whitespace-delimited token under [column] (0-based) in [line].
     * Returns empty string when the column is out of range or on whitespace.
     */
    fun extractTokenAt(line: String, column: Int): Pair<String, IntRange> {
        if (line.isEmpty()) return "" to IntRange.EMPTY
        // Allow probing one past the last char (cursor often sits there).
        val col = column.coerceIn(0, (line.length - 1).coerceAtLeast(0))
        if (col >= line.length) return "" to IntRange.EMPTY
        if (line[col].isWhitespace()) {
            // Prefer token to the left of a space (common when tapping mid-gap).
            var left = col - 1
            while (left >= 0 && line[left].isWhitespace()) left--
            if (left < 0) return "" to IntRange.EMPTY
            return extractTokenAt(line, left)
        }
        var start = col
        var end = col
        while (start > 0 && !line[start - 1].isWhitespace()) start--
        while (end < line.lastIndex && !line[end + 1].isWhitespace()) end++
        return line.substring(start, end + 1) to (start..end)
    }

    /**
     * Best URL on [line] that covers [column], or null.
     */
    fun findUrlAt(line: String, column: Int): String? = findUrlSpanAt(line, column)?.url

    /**
     * Best URL span on [line] that covers [column], including column bounds for selection.
     */
    fun findUrlSpanAt(line: String, column: Int): UrlSpan? {
        if (line.isEmpty()) return null
        val col = column.coerceIn(0, (line.length - 1).coerceAtLeast(0))
        for (match in URL_REGEX.findAll(line)) {
            val raw = match.value
            val cleaned = raw.replace(TRAILING_URL_PUNCT, "")
            if (cleaned.isEmpty()) continue
            val start = match.range.first
            val endExclusive = start + cleaned.length
            if (col in start until endExclusive) {
                return UrlSpan(url = normalizeUrl(cleaned), start = start, endExclusive = endExclusive)
            }
        }
        // Token itself may be a URL even if regex word-boundary failed (e.g. after `=`).
        val (token, range) = extractTokenAt(line, col)
        if (range == IntRange.EMPTY) return null
        val trimmed = token.trim().replace(TRAILING_URL_PUNCT, "")
        if (!looksLikeUrl(trimmed)) return null
        // Strip trailing sentence punctuation from the on-line span.
        var endExclusive = range.last + 1
        while (endExclusive > range.first && line[endExclusive - 1] in TRAILING_URL_CHARS) {
            endExclusive--
        }
        return UrlSpan(
            url = normalizeUrl(trimmed),
            start = range.first,
            endExclusive = endExclusive.coerceAtLeast(range.first + 1)
        )
    }

    private val TRAILING_URL_CHARS = setOf('.', ',', ';', ':', '!', '?', ')')

    fun looksLikeUrl(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = text.trim().replace(TRAILING_URL_PUNCT, "")
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("ftp://", ignoreCase = true) ||
            t.startsWith("www.", ignoreCase = true)
    }

    /** Ensure the string is a valid ACTION_VIEW URI (add https for bare www.). */
    fun normalizeUrl(url: String): String {
        val t = url.trim().replace(TRAILING_URL_PUNCT, "")
        return when {
            t.startsWith("www.", ignoreCase = true) -> "https://$t"
            else -> t
        }
    }

    /**
     * Hit-test [column] on a single terminal row string (may include trailing spaces).
     */
    fun hitTestLine(line: String, column: Int): CellHit {
        val trimmedForDisplay = line.trimEnd()
        val (token, range) = extractTokenAt(line, column)
        val urlSpan = findUrlSpanAt(line, column)
        return CellHit(
            lineText = trimmedForDisplay,
            column = column,
            token = token,
            tokenStart = if (range == IntRange.EMPTY) 0 else range.first,
            tokenEnd = if (range == IntRange.EMPTY) 0 else range.last + 1,
            url = urlSpan?.url,
            urlStart = urlSpan?.start ?: 0,
            urlEnd = urlSpan?.endExclusive ?: 0
        )
    }

    /** Every URL span on [line] (non-overlapping, left-to-right). */
    fun findAllUrlSpans(line: String): List<UrlSpan> {
        if (line.isEmpty()) return emptyList()
        val results = mutableListOf<UrlSpan>()
        var coveredUntil = -1
        for (match in URL_REGEX.findAll(line)) {
            val raw = match.value
            val cleaned = raw.replace(TRAILING_URL_PUNCT, "")
            if (cleaned.isEmpty()) continue
            val start = match.range.first
            if (start < coveredUntil) continue
            val endExclusive = start + cleaned.length
            results += UrlSpan(
                url = normalizeUrl(cleaned),
                start = start,
                endExclusive = endExclusive
            )
            coveredUntil = endExclusive
        }
        return results
    }

    /** Tag used on [androidx.compose.ui.text.AnnotatedString] string annotations for links. */
    const val URL_ANNOTATION_TAG = "URL"
}
