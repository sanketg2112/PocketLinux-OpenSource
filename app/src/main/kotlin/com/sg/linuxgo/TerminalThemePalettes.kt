package com.sg.linuxgo

/**
 * 16-color ANSI palettes for each [com.sg.linuxgo.ui.screens.TerminalTheme] id.
 * TUI apps (htop, btop, vim, lazygit) read these via SGR 30–37 / 90–97 / 40–47.
 */
object TerminalThemePalettes {

    fun ansi16(themeId: String): IntArray {
        val hex = PALETTES[themeId] ?: PALETTES.getValue("default")
        return IntArray(16) { i ->
            TerminalColorMath.parseHexRgb(hex[i])
                ?: TerminalColorMath.packRgb(0, 0, 0)
        }
    }

    fun isLightTheme(themeId: String): Boolean = themeId == "light"

    /**
     * COLORFGBG for ncurses / some TUIs: "fgIndex;bgIndex".
     */
    fun colorFgBg(themeId: String): String =
        if (isLightTheme(themeId)) "0;15" else "15;0"

    private val PALETTES: Map<String, Array<String>> = mapOf(
        "default" to arrayOf(
            "#0A0C0F", "#BF616A", "#A3BE8C", "#EBCB8B",
            "#81A1C1", "#B48EAD", "#6EB0BA", "#E5E9F0",
            "#4C566A", "#D08770", "#8FBCBB", "#EBCB8B",
            "#88C0D0", "#B48EAD", "#8FBCBB", "#ECEFF4"
        ),
        "podroid" to arrayOf(
            "#0A0A0A", "#F87171", "#4ADE80", "#FACC15",
            "#60A5FA", "#C084FC", "#2DD4BF", "#EDEDED",
            "#404040", "#FCA5A5", "#86EFAC", "#FDE047",
            "#93C5FD", "#D8B4FE", "#5EEAD4", "#FFFFFF"
        ),
        "dracula" to arrayOf(
            "#21222C", "#FF5555", "#50FA7B", "#F1FA8C",
            "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
            "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5",
            "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF"
        ),
        "solarized_dark" to arrayOf(
            "#073642", "#DC322F", "#859900", "#B58900",
            "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
            "#002B36", "#CB4B16", "#586E75", "#657B83",
            "#839496", "#6C71C4", "#93A1A1", "#FDF6E3"
        ),
        "monokai" to arrayOf(
            "#272822", "#F92672", "#A6E22E", "#E6DB74",
            "#66D9EF", "#AE81FF", "#A1EFE4", "#F8F8F2",
            "#75715E", "#F92672", "#A6E22E", "#E6DB74",
            "#66D9EF", "#AE81FF", "#A1EFE4", "#F9F8F5"
        ),
        "retro_green" to arrayOf(
            "#001100", "#00AA00", "#00FF00", "#88FF88",
            "#007700", "#00CC66", "#33FF99", "#00FF00",
            "#003300", "#00CC00", "#66FF66", "#CCFFCC",
            "#009900", "#00FF88", "#99FFBB", "#E0FFE0"
        ),
        "light" to arrayOf(
            "#1C1C1E", "#C62828", "#2E7D32", "#F9A825",
            "#1565C0", "#6A1B9A", "#00838F", "#3C3C3E",
            "#6E6E73", "#E53935", "#43A047", "#FDD835",
            "#1E88E5", "#8E24AA", "#00ACC1", "#111111"
        ),
        "nord" to arrayOf(
            "#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B",
            "#81A1C1", "#B48EAD", "#88C0D0", "#E5E9F0",
            "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B",
            "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4"
        ),
        "one_dark" to arrayOf(
            "#1E222A", "#E06C75", "#98C379", "#E5C07B",
            "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
            "#5C6370", "#E06C75", "#98C379", "#E5C07B",
            "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF"
        ),
        "gruvbox" to arrayOf(
            "#282828", "#CC241D", "#98971A", "#D79921",
            "#458588", "#B16286", "#689D6A", "#A89984",
            "#928374", "#FB4934", "#B8BB26", "#FABD2F",
            "#83A598", "#D3869B", "#8EC07C", "#EBDBB2"
        ),
        "aura" to arrayOf(
            "#15141B", "#FF6767", "#61FFCA", "#FFCA85",
            "#A277FF", "#A277FF", "#61FFCA", "#EDECEE",
            "#6D6D6D", "#FFCA85", "#A277FF", "#FFCA85",
            "#A277FF", "#F694FF", "#61FFCA", "#FFFFFF"
        ),
        "cyberpunk" to arrayOf(
            "#0F0F1A", "#FF007F", "#00FF9F", "#FCEE0A",
            "#00B3FF", "#FF00A0", "#00FFCC", "#E6F1FF",
            "#3D3D5C", "#FF4DA6", "#5CFFC0", "#FFF36B",
            "#4DC3FF", "#FF66C2", "#66FFE0", "#FFFFFF"
        ),
        "sunset" to arrayOf(
            "#1E121E", "#FF5E7E", "#FF9A76", "#FFD56F",
            "#C084FC", "#FF7AB2", "#FFB4A2", "#FBE4FF",
            "#5A3A5A", "#FF8FA3", "#FFB899", "#FFE199",
            "#D4A5FF", "#FFA3C8", "#FFC9BC", "#FFFFFF"
        ),
        "deep_ocean" to arrayOf(
            "#0B132B", "#EE6C4D", "#80ED99", "#E9C46A",
            "#48CAE4", "#9B5DE5", "#00B4D8", "#E2E8F0",
            "#1C2541", "#F4A261", "#B7E4C7", "#F4D35E",
            "#90E0EF", "#C77DFF", "#48CAE4", "#F8FAFC"
        ),
        "forest_moss" to arrayOf(
            "#131E17", "#C45C26", "#81C784", "#D4E157",
            "#4DB6AC", "#AED581", "#66BB6A", "#E8F5E9",
            "#2E4636", "#E07040", "#A5D6A7", "#E6EE9C",
            "#80CBC4", "#C5E1A5", "#81C784", "#F1F8E9"
        ),
        "tokyo_night" to arrayOf(
            "#15161E", "#F7768E", "#9ECE6A", "#E0AF68",
            "#7AA2F7", "#BB9AF7", "#7DCFFF", "#A9B1D6",
            "#414868", "#F7768E", "#9ECE6A", "#E0AF68",
            "#7AA2F7", "#BB9AF7", "#7DCFFF", "#C0CAF5"
        ),
        "rose_pine" to arrayOf(
            "#26233A", "#EB6F92", "#31748F", "#F6C177",
            "#9CCFD8", "#C4A7E7", "#EBBCBA", "#E0DEF4",
            "#6E6A86", "#EB6F92", "#31748F", "#F6C177",
            "#9CCFD8", "#C4A7E7", "#EBBCBA", "#E0DEF4"
        ),
        "synthwave" to arrayOf(
            "#2B213A", "#FE4450", "#72F1B8", "#FEDE5D",
            "#36F9F6", "#F92AAD", "#03EDF9", "#FFFFFF",
            "#495495", "#FE4450", "#72F1B8", "#FEDE5D",
            "#36F9F6", "#F97E72", "#03EDF9", "#FFFFFF"
        ),
        "espresso" to arrayOf(
            "#2D2424", "#C75B39", "#C9A227", "#E0A96D",
            "#8D6E63", "#A1887F", "#D7CCC8", "#EFE8E8",
            "#5D4037", "#E07A5F", "#F2CC8F", "#F4D6A0",
            "#BCAAA4", "#D7CCC8", "#EFEBE9", "#FFF8F0"
        ),
        "cyber_lime" to arrayOf(
            "#050805", "#FF2A55", "#CCFF00", "#F5FF7A",
            "#00E5A8", "#B8FF3C", "#7CFF6B", "#EEFFEE",
            "#1A331A", "#FF6B88", "#E6FF66", "#FFFF99",
            "#5CFFC8", "#D4FF70", "#A6FF8F", "#FFFFFF"
        ),
        "iceberg" to arrayOf(
            "#161821", "#E27878", "#B4BE82", "#E2A478",
            "#84A0C6", "#A093C7", "#89B8C2", "#D2D4DE",
            "#6B7089", "#E98989", "#C0CA8E", "#E9B189",
            "#91ACD1", "#ADA0D3", "#95C4CE", "#C6C8D1"
        )
    )
}
