package com.sg.linuxgo

import android.content.SharedPreferences
import java.io.File

/**
 * Resolve external (container-card) terminal appearance + shell from the guest rootfs
 * so it can match the GUI xfce4-terminal + user shell themes (starship/oh-my).
 */
object GuestTerminalAppearance {
    const val PREF_THEME_SOURCE = "terminal_theme_source"
    const val SOURCE_MATCH_GUI = "match_gui"
    const val SOURCE_APP = "app"

    enum class Shell(val binaryCandidates: List<String>, val loginArgs: List<String>) {
        FISH(listOf("/usr/bin/fish", "/bin/fish"), listOf("-l")),
        ZSH(listOf("/bin/zsh", "/usr/bin/zsh"), listOf("-l")),
        BASH(listOf("/bin/bash", "/usr/bin/bash"), listOf("--login")),
        SH(listOf("/bin/sh", "/usr/bin/sh"), listOf("-l"))
    }

    data class Resolved(
        val scheme: TerminalColorScheme,
        val shell: Shell,
        /** Absolute host path to a .ttf/.otf under the rootfs, if found. */
        val fontFile: File?,
        val fontFamilyHint: String?,
        val matchedGui: Boolean,
        val terminalRcPath: String?,
        val prompt: GuestTerminalLaunch.GuestPrompt = GuestTerminalLaunch.GuestPrompt(
            GuestTerminalLaunch.PromptTheme.NONE, null, null
        ),
        val cursorBlinks: Boolean = false,
        val cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
        val extraEnv: Array<String> = emptyArray()
    )

    fun themeSource(prefs: SharedPreferences?): String {
        val raw = prefs?.getString(PREF_THEME_SOURCE, SOURCE_MATCH_GUI) ?: SOURCE_MATCH_GUI
        return if (raw == SOURCE_APP) SOURCE_APP else SOURCE_MATCH_GUI
    }

    fun isMatchGui(prefs: SharedPreferences?): Boolean =
        themeSource(prefs) == SOURCE_MATCH_GUI

    /**
     * Resolve appearance for a container rootfs.
     * When source is Match GUI, prefer xfce4-terminalrc; fall back to [appScheme].
     */
    fun resolve(
        rootfs: File,
        username: String,
        prefs: SharedPreferences?,
        appScheme: TerminalColorScheme
    ): Resolved {
        val shell = detectShell(rootfs, username)
        val matchGui = isMatchGui(prefs)
        val prompt = GuestTerminalLaunch.detectPrompt(rootfs, username)
        val homeDir = if (username == "root") "/root" else "/home/${username.trim().ifBlank { "PocketLinux" }}"
        val shellBin = resolveShellBinary(rootfs, shell)
        val extraEnv = GuestTerminalLaunch.extraEnvPairs(rootfs, username, homeDir, shellBin, prompt)

        val parsed = if (matchGui) loadGuestTerminalPrefs(rootfs, username) else null
        if (parsed != null) {
            val scheme = XfceTerminalRcParser.toColorScheme(parsed.settings, appScheme)
            val fontFile = findFontFile(rootfs, scheme.fontName)
            return Resolved(
                scheme = scheme,
                shell = shell,
                fontFile = fontFile,
                fontFamilyHint = scheme.fontName,
                matchedGui = true,
                terminalRcPath = parsed.sourcePath,
                prompt = prompt,
                cursorBlinks = parsed.cursorBlinks,
                cursorShape = parsed.cursorShape,
                extraEnv = extraEnv
            )
        }
        // Match GUI with no terminalrc yet, or app theme: use app scheme chrome.
        val fontHint = if (matchGui) {
            parsedFontHint(rootfs, username) ?: "MesloLGS NF"
        } else {
            null
        }
        val fontFile = findFontFile(rootfs, fontHint)
        return Resolved(
            scheme = appScheme.copy(
                source = if (matchGui) TerminalColorScheme.Source.BUILTIN else appScheme.source
            ),
            shell = shell,
            fontFile = fontFile,
            fontFamilyHint = fontHint ?: appScheme.fontName,
            matchedGui = false,
            terminalRcPath = findTerminalRc(rootfs, username)?.absolutePath,
            prompt = prompt,
            extraEnv = extraEnv
        )
    }

    data class LoadedGuestPrefs(
        val settings: XfceTerminalRcParser.Parsed,
        val sourcePath: String,
        val cursorBlinks: Boolean,
        val cursorShape: TerminalCursorShape
    )

    /**
     * Prefer the user's xfce4-terminalrc, then xfconf XML (XFCE 4.18+), then system defaults.
     */
    fun loadGuestTerminalPrefs(rootfs: File, username: String): LoadedGuestPrefs? {
        val files = mutableListOf<File>()
        findTerminalRc(rootfs, username)?.let { files += it }
        findXfconf(rootfs, username)?.let { files += it }
        findSystemTerminalRc(rootfs)?.let { files += it }
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (text.isBlank()) continue
            val parsed = if (file.name.endsWith(".xml", ignoreCase = true)) {
                XfceTerminalXfconfParser.parse(text)
            } else {
                XfceTerminalRcParser.parse(text)
            }
            if (parsed.colorForeground == null && parsed.colorBackground == null &&
                parsed.colorPalette == null && parsed.fontFamily == null
            ) {
                continue
            }
            return LoadedGuestPrefs(
                settings = parsed,
                sourcePath = file.absolutePath,
                cursorBlinks = parsed.cursorBlinks,
                cursorShape = parsed.cursorShape
            )
        }
        return null
    }

    fun findTerminalRc(rootfs: File, username: String): File? {
        val user = username.trim().ifBlank { "PocketLinux" }
        val candidates = mutableListOf<File>()
        if (user != "root") {
            candidates += File(rootfs, "home/$user/.config/xfce4/terminal/terminalrc")
        }
        candidates += File(rootfs, "root/.config/xfce4/terminal/terminalrc")
        File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { home ->
            candidates += File(home, ".config/xfce4/terminal/terminalrc")
        }
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    fun findXfconf(rootfs: File, username: String): File? {
        val user = username.trim().ifBlank { "PocketLinux" }
        val candidates = mutableListOf<File>()
        if (user != "root") {
            candidates += File(
                rootfs,
                "home/$user/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml"
            )
        }
        candidates += File(
            rootfs,
            "root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml"
        )
        File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { home ->
            candidates += File(home, ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml")
        }
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    fun findSystemTerminalRc(rootfs: File): File? {
        val candidates = listOf(
            File(rootfs, "etc/xdg/xfce4/terminal/terminalrc"),
            File(rootfs, "etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml")
        )
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    private fun parsedFontHint(rootfs: File, username: String): String? {
        val loaded = loadGuestTerminalPrefs(rootfs, username) ?: return null
        return loaded.settings.fontFamily
    }

    fun detectShell(rootfs: File, username: String): Shell {
        val user = username.trim().ifBlank { "PocketLinux" }
        val passwdShell = readPasswdShell(rootfs, user)
        val zshOk = shellBinaryExists(rootfs, Shell.ZSH)
        val bashOk = shellBinaryExists(rootfs, Shell.BASH)

        val fishOk = shellBinaryExists(rootfs, Shell.FISH)
        if (passwdShell != null) {
            when {
                passwdShell.endsWith("/fish") || passwdShell == "fish" ->
                    if (fishOk) return Shell.FISH
                passwdShell.endsWith("/zsh") || passwdShell == "zsh" ->
                    if (zshOk) return Shell.ZSH
                passwdShell.endsWith("/bash") || passwdShell == "bash" ->
                    if (bashOk) return Shell.BASH
                passwdShell.endsWith("/sh") || passwdShell == "sh" ->
                    if (shellBinaryExists(rootfs, Shell.SH)) return Shell.SH
            }
        }

        val home = guestHome(rootfs, user)
        val zshrc = File(home, ".zshrc")
        if (zshOk && zshrc.isFile && zshrc.length() > 0L) {
            return Shell.ZSH
        }
        val fishConfig = File(home, ".config/fish/config.fish")
        if (fishOk && fishConfig.isFile) {
            return Shell.FISH
        }
        if (bashOk) return Shell.BASH
        if (zshOk) return Shell.ZSH
        return Shell.SH
    }

    fun shellBinaryExists(rootfs: File, shell: Shell): Boolean =
        shell.binaryCandidates.any { File(rootfs, it.removePrefix("/")).isFile }

    fun resolveShellBinary(rootfs: File, shell: Shell): String {
        for (c in shell.binaryCandidates) {
            if (File(rootfs, c.removePrefix("/")).isFile) return c
        }
        return shell.binaryCandidates.first()
    }

    /**
     * Search guest font dirs for a file matching [fontFamily] (fuzzy).
     * Also accepts common Nerd Font fallbacks when family is null/empty.
     */
    fun findFontFile(rootfs: File, fontFamily: String?): File? {
        val needles = buildList {
            val fam = fontFamily?.trim().orEmpty()
            if (fam.isNotEmpty()) {
                add(fam)
                add(fam.replace(" ", ""))
                add(fam.replace(" Nerd Font", "", ignoreCase = true))
                add(fam.replace(" NF", "", ignoreCase = true))
            }
            // Common nerd fonts installed by setup / user
            add("MesloLGS")
            add("Meslo")
            add("FiraCode")
            add("JetBrainsMono")
            add("NerdFont")
            add("Nerd Font")
        }.distinct()

        val searchRoots = listOf(
            File(rootfs, "usr/share/fonts"),
            File(rootfs, "usr/local/share/fonts"),
            File(rootfs, "home"),
            File(rootfs, "root/.local/share/fonts"),
            File(rootfs, "root/.fonts")
        )

        val fonts = mutableListOf<File>()
        for (root in searchRoots) {
            collectFontFiles(root, fonts, maxFiles = 400)
        }
        if (fonts.isEmpty()) return null

        for (needle in needles) {
            val n = needle.lowercase()
            if (n.isEmpty()) continue
            val matches = fonts.filter { f ->
                val name = f.name.lowercase()
                name.contains(n) ||
                    name.replace(" ", "").contains(n.replace(" ", ""))
            }
            val hit = pickBestFontFile(matches, fontFamily)
            if (hit != null) return hit
        }
        // Prefer any nerd/powerline mono over random first font
        pickBestFontFile(
            fonts.filter {
                val n = it.name.lowercase()
                n.contains("nerd") || n.contains("meslo") || n.contains("powerline")
            },
            fontFamily
        )?.let { return it }
        return null
    }

    /**
     * Rank a guest font file for terminal use. Prefer Mono Regular faces;
     * penalize Propo / Variable / Italic / CJK so Match GUI does not load a
     * wide family variant that stretches letter spacing on the grid.
     */
    fun scoreGuestFontFile(fileName: String, familyHint: String?): Int {
        val n = fileName.lowercase()
        val hint = familyHint?.lowercase().orEmpty()
        var score = 0
        if (n.contains("mono")) score += 50
        if (n.contains("regular") || n.contains("-reg") || n.contains("_reg")) {
            score += 20
        }
        if (n.contains("nerd") || n.contains("nf-") || n.contains("nf.")) score += 5
        val hintWantsPropo = hint.contains("propo")
        val hintWantsMono = hint.contains("mono")
        if (n.contains("propo") && !hintWantsPropo) score -= 100
        if (n.contains("variable") && !hint.contains("variable")) score -= 25
        if ((n.contains("italic") || n.contains("oblique")) && !hint.contains("italic")) score -= 40
        if (n.contains("cjk") && !hint.contains("cjk")) score -= 50
        if (n.contains("emoji") && !hint.contains("emoji")) score -= 50
        if (hintWantsMono && n.contains("mono")) score += 30
        if (hintWantsPropo && n.contains("propo")) score += 80
        if (hintWantsPropo && n.contains("mono") && !n.contains("propo")) score -= 40
        if (n.endsWith(".ttc") || n.endsWith(".otc")) score -= 8
        return score
    }

    fun pickBestFontFile(candidates: List<File>, familyHint: String?): File? {
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<File> { scoreGuestFontFile(it.name, familyHint) }
                .thenBy { it.name.length }
        )
    }

    fun guestHome(rootfs: File, username: String): File {
        val user = username.trim().ifBlank { "PocketLinux" }
        return if (user == "root") File(rootfs, "root") else File(rootfs, "home/$user")
    }

    fun readPasswdShell(rootfs: File, username: String): String? {
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile) return null
        val lines = runCatching { passwd.readLines() }.getOrNull() ?: return null
        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue
            val parts = line.split(':')
            if (parts.size < 7) continue
            if (parts[0] == username) {
                return parts[6].trim().ifEmpty { null }
            }
        }
        return null
    }

    private fun collectFontFiles(dir: File, out: MutableList<File>, maxFiles: Int) {
        if (out.size >= maxFiles || !dir.isDirectory) return
        val stack = ArrayDeque<File>()
        stack.add(dir)
        var visited = 0
        while (stack.isNotEmpty() && out.size < maxFiles && visited < 2000) {
            val d = stack.removeFirst()
            visited++
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    // User fonts under home/*/.local/share/fonts or home/*/.fonts
                    val name = c.name
                    if (d.name == "home" && !c.name.startsWith(".")) {
                        stack.add(c)
                    } else if (
                        name == "fonts" || name == ".fonts" || name == "truetype" ||
                        name == "opentype" || name == "TTF" || name == "OTF" ||
                        name == "share" || name == ".local" || name == "nerd-fonts" ||
                        name.contains("Nerd", ignoreCase = true) ||
                        name.contains("Meslo", ignoreCase = true)
                    ) {
                        stack.add(c)
                    } else if (d.absolutePath.contains("/share/fonts") || d.absolutePath.contains("/.fonts")) {
                        stack.add(c)
                    }
                } else if (c.isFile) {
                    val n = c.name.lowercase()
                    if (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")) {
                        out.add(c)
                        if (out.size >= maxFiles) return
                    }
                }
            }
        }
    }
}
