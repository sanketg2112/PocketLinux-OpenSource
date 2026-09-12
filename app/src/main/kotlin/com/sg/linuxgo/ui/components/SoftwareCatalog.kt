package com.sg.linuxgo.ui.components

import com.sg.linuxgo.Bootstrap

object SoftwareCatalog {
    fun defaultCatalogue(): List<Bootstrap.Choice> = listOf(
        Bootstrap.Choice("chromium", "Chromium Web Browser"),
        Bootstrap.Choice("firefox", "Firefox Web Browser"),
        Bootstrap.Choice("vlc", "VLC Media Player"),
        Bootstrap.Choice("mpv", "MPV Player"),
        Bootstrap.Choice("code-oss", "VS Code (OSS)"),
        Bootstrap.Choice("geany", "Geany IDE"),
        Bootstrap.Choice("thunar", "Thunar File Manager"),
        Bootstrap.Choice("pcmanfm", "PCManFM File Manager"),
        Bootstrap.Choice("dolphin", "Dolphin File Manager"),
        Bootstrap.Choice("ranger", "Ranger (Terminal FM)"),
        Bootstrap.Choice("nnn", "nnn (Fast Terminal FM)"),
        Bootstrap.Choice("neovim", "Neovim Editor"),
        Bootstrap.Choice("gimp", "GIMP Photo Editor"),
        Bootstrap.Choice("inkscape", "Inkscape Vector Graphics")
    )
}
