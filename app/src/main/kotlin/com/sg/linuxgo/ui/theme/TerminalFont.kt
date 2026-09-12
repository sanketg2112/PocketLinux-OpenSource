package com.sg.linuxgo.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

enum class TerminalFontOption(
    val id: String,
    val displayName: String,
    val assetPath: String?,
    /** True when the asset is a Nerd Font (icons baked in). Fallback still applies. */
    val isNerdFont: Boolean = false
) {
    MESLO("meslo", "MesloLGS Nerd Font", "fonts/MesloLGS-NF-Regular.ttf", isNerdFont = true),
    JETBRAINS_MONO_NF(
        "jetbrains_mono_nf",
        "JetBrainsMono Nerd Font",
        // Bundled JetBrainsMono-400 is already Nerd Font Mono Regular (NF 3.3).
        "fonts/JetBrainsMono-400.ttf",
        isNerdFont = true
    ),
    FIRA_CODE_NF(
        "fira_code_nf",
        "FiraCode Nerd Font",
        "fonts/FiraCodeNerdFontMono-Regular.ttf",
        isNerdFont = true
    ),
    HACK_NF(
        "hack_nf",
        "Hack Nerd Font",
        "fonts/HackNerdFontMono-Regular.ttf",
        isNerdFont = true
    ),
    CASKAYDIA_COVE_NF(
        "caskaydia_cove_nf",
        "CaskaydiaCove Nerd Font",
        "fonts/CaskaydiaCoveNerdFontMono-Regular.ttf",
        isNerdFont = true
    ),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", "fonts/JetBrainsMono-400.ttf"),
    IOSEVKA("iosevka", "Iosevka", "fonts/Iosevka-Regular.ttf"),
    SOMETYPE_MONO("sometype_mono", "Sometype Mono", "fonts/SometypeMono-Regular.ttf"),
    M_PLUS_CODE_LATIN("m_plus_code_latin", "M+ Code Latin", "fonts/MPLUSCodeLatin-Regular.ttf"),
    PT_MONO("pt_mono", "PT Mono", "fonts/PTMono-Regular.ttf"),
    SHARE_TECH_MONO("share_tech_mono", "Share Tech Mono", "fonts/ShareTechMono-Regular.ttf"),
    FRAGMENT_MONO("fragment_mono", "Fragment Mono", "fonts/FragmentMono-Regular.ttf"),
    INCONSOLATA("inconsolata", "Inconsolata", "fonts/Inconsolata-Regular.ttf"),
    FIRA_CODE("fira_code", "Fira Code", "fonts/FiraCode-Regular.ttf"),
    ROBOTO_MONO("roboto_mono", "Roboto Mono", "fonts/RobotoMono-Regular.ttf"),
    SOURCE_CODE_PRO("source_code_pro", "Source Code Pro", "fonts/SourceCodePro-Regular.ttf"),
    CASCADIA_CODE("cascadia_code", "Cascadia Code", "fonts/CascadiaCode-Regular.ttf"),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono", "fonts/IBMPlexMono-Regular.ttf"),
    SPACE_MONO("space_mono", "Space Mono", "fonts/SpaceMono-Regular.ttf"),
    UBUNTU_MONO("ubuntu_mono", "Ubuntu Mono", "fonts/UbuntuMono-Regular.ttf"),
    NOTO_SANS_MONO("noto_sans_mono", "Noto Sans Mono", "fonts/NotoSansMono-Regular.ttf"),
    SYSTEM_MONOSPACE("system_monospace", "System Monospace", null);

    companion object {
        fun fromId(id: String): TerminalFontOption =
            entries.firstOrNull { it.id == id } ?: MESLO
    }

    /**
     * Extra Nerd Font families are hidden until their TTF is packaged.
     * Meslo and all non-NF options stay listed (missing non-NF files already
     * fall back at load time).
     */
    fun isListed(context: android.content.Context): Boolean {
        if (!isNerdFont || id == MESLO.id) return true
        val path = assetPath ?: return false
        return try {
            context.assets.open(path).use { true }
        } catch (_: Exception) {
            false
        }
    }

    fun toFontFamily(context: Context): FontFamily {
        val path = assetPath ?: return FontFamily.Monospace
        return try {
            FontFamily(
                Font(
                    assetManager = context.assets,
                    path = path,
                    weight = FontWeight.Normal
                )
            )
        } catch (_: Exception) {
            FontFamily.Monospace
        }
    }
}

@Composable
fun rememberTerminalFontFamily(fontId: String): FontFamily {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(fontId, context) {
        TerminalFontOption.fromId(fontId).toFontFamily(context)
    }
}