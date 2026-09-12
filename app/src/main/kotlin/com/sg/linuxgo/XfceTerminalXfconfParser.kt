package com.sg.linuxgo

/**
 * Parser for XFCE 4.18+ xfconf channel XML (`xfce4-terminal.xml`).
 * Modern xfce4-terminal writes here instead of (or in addition to) terminalrc.
 */
object XfceTerminalXfconfParser {

    fun parse(xml: String): XfceTerminalRcParser.Parsed {
        if (xml.isBlank()) return XfceTerminalRcParser.Parsed()
        val keys = linkedMapOf<String, String>()
        val prop = Regex(
            """<property\s+name="([^"]+)"\s+type="([^"]+)"(?:\s+value="([^"]*)")?""",
            RegexOption.IGNORE_CASE
        )
        for (m in prop.findAll(xml)) {
            val name = m.groupValues[1].trim()
            val value = m.groupValues.getOrNull(3).orEmpty()
            if (name.isNotEmpty()) keys[xfconfToRcKey(name)] = unescapeXml(value)
        }
        // Rebuild an rc-like blob so ColorPalette / FontName reuse the same parser.
        val fakeRc = buildString {
            keys.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
        }
        return XfceTerminalRcParser.parse(fakeRc)
    }

    /**
     * xfconf uses kebab-case (`color-foreground`); terminalrc uses CamelCase.
     */
    fun xfconfToRcKey(name: String): String {
        return when (name.lowercase()) {
            "color-foreground" -> "ColorForeground"
            "color-background" -> "ColorBackground"
            "color-cursor" -> "ColorCursor"
            "color-cursor-foreground" -> "ColorCursorForeground"
            "color-bold" -> "ColorBold"
            "color-palette" -> "ColorPalette"
            "font-name" -> "FontName"
            "misc-cursor-blinks" -> "MiscCursorBlinks"
            "misc-cursor-shape" -> "MiscCursorShape"
            "scrolling-lines" -> "ScrollingLines"
            "run-custom-command" -> "RunCustomCommand"
            "command" -> "Command"
            "color-bold-is-bright" -> "ColorBoldIsBright"
            "misc-bell" -> "MiscBell"
            else -> name
        }
    }

    private fun unescapeXml(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
}
