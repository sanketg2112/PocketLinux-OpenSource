package com.sg.linuxgo

/**
 * OSC 4 / 10 / 11 / 12 set + query. TUI apps (nvim, htop, btop) use `?` queries
 * to learn the active theme so their chrome matches the terminal canvas.
 */
object TerminalOscColor {

    data class Result(
        val scheme: TerminalColorScheme,
        val currentFg: Int? = null,
        val replies: List<String> = emptyList(),
        val changed: Boolean = false
    )

    fun apply(
        code: String,
        data: String,
        scheme: TerminalColorScheme,
        currentFg: Int
    ): Result? = when (code) {
        "4" -> applyPalette(data, scheme)
        "10" -> applyDynamic("10", data, scheme, queryColor = scheme.defaultFg) { next ->
            val prev = scheme.defaultFg
            Result(
                scheme = scheme.withDefaultFg(next),
                currentFg = if (currentFg == prev) next else null,
                changed = true
            )
        }
        "11" -> applyDynamic("11", data, scheme, queryColor = scheme.defaultBg) { next ->
            Result(scheme = scheme.withDefaultBg(next), changed = true)
        }
        "12" -> applyDynamic("12", data, scheme, queryColor = scheme.cursorColor) { next ->
            Result(scheme = scheme.withCursor(next), changed = true)
        }
        else -> null
    }

    fun rgbColonSpec(argb: Int): String {
        fun chan(v: Int): String {
            val eight = v.coerceIn(0, 255)
            val sixteen = (eight shl 8) or eight
            return sixteen.toString(16).padStart(4, '0')
        }
        return "rgb:${chan(TerminalColorMath.red(argb))}/" +
            "${chan(TerminalColorMath.green(argb))}/" +
            chan(TerminalColorMath.blue(argb))
    }

    fun oscReply(ps: String, pt: String): String = "\u001B]$ps;$pt\u0007"

    private fun applyDynamic(
        code: String,
        data: String,
        scheme: TerminalColorScheme,
        queryColor: Int,
        set: (Int) -> Result
    ): Result {
        val spec = TerminalOsc.firstColorSpec(data)
        if (spec == "?") {
            return Result(
                scheme = scheme,
                replies = listOf(oscReply(code, rgbColonSpec(queryColor)))
            )
        }
        val color = TerminalColorMath.parseColorSpec(spec) ?: return Result(scheme)
        return set(color)
    }

    private fun applyPalette(data: String, scheme: TerminalColorScheme): Result {
        if (data.isBlank()) return Result(scheme)
        val parts = data.split(';')
        var i = 0
        var next = scheme
        var changed = false
        val replies = ArrayList<String>(2)
        while (i + 1 < parts.size) {
            val index = parts[i].trim().toIntOrNull()
            val spec = parts[i + 1].trim()
            i += 2
            if (index == null) continue
            if (spec == "?") {
                val color = paletteColor(index, next) ?: continue
                replies += oscReply("4", "$index;${rgbColonSpec(color)}")
                continue
            }
            if (index !in 0..15 || spec.isEmpty()) continue
            val color = TerminalColorMath.parseColorSpec(spec) ?: continue
            next = next.withPaletteColor(index, color)
            changed = true
        }
        return Result(scheme = next, replies = replies, changed = changed)
    }

    private fun paletteColor(index: Int, scheme: TerminalColorScheme): Int? = when (index) {
        in 0..15 -> scheme.color(index)
        in 16..255 -> TerminalPalette.get256Color(index, scheme)
        else -> null
    }
}
