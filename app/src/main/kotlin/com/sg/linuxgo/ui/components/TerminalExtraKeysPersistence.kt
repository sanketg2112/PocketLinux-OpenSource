package com.sg.linuxgo.ui.components

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Shared model + persistence for the terminal extra-keys bar (top strip + expanded panel).
 * Used by [SpecialKeysBar] at runtime and by Keyboard settings for editing.
 *
 * Editing uses **groups of [KEYS_PER_GROUP]**; the live terminal panel
 * renders **[KEYS_PER_PANEL_ROW]** keys per row (two groups side by side).
 */
const val EXTRA_KEYS_PREFS = "PocketLinuxExtraKeysPrefs"
private const val PREF_TOP = "top_bar_keys_v2"
private const val PREF_PANEL = "panel_keys_v2"
private const val PREF_CUSTOM_LEGACY = "custom_keys"
private const val PREF_REVISION = "keys_revision"
/** One-time prepend of ^C/^Z/… combos for installs that saved the older panel layout. */
private const val PREF_CTRL_COMBOS_MIGRATED = "ctrl_combos_migrated_v1"

/** How many keys form one editable group in settings. */
const val KEYS_PER_GROUP = 4

/** How many keys per row in the live terminal expanded panel. */
const val KEYS_PER_PANEL_ROW = 8

const val ADD_KEY_ID = "__add_custom__"
const val ADD_KEY_NAME = "ADD_CUSTOM_KEY"

data class ExtraKey(
    val id: String,
    val label: String,
    val keyName: String,
    val isArrow: Boolean = false,
    val isToggle: Boolean = false,
    val isCustom: Boolean = false
)

data class ExtraKeysLayout(
    val topKeys: List<ExtraKey>,
    val panelKeys: List<ExtraKey>
)

fun extraKeysPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(EXTRA_KEYS_PREFS, Context.MODE_PRIVATE)

fun loadExtraKeysLayout(prefs: SharedPreferences): ExtraKeysLayout {
    val top = loadKeyList(prefs, PREF_TOP) ?: defaultTopKeys()
    val savedPanel = loadKeyList(prefs, PREF_PANEL)?.filter {
        it.id != ADD_KEY_ID && it.keyName != ADD_KEY_NAME
    }
    val panel = if (savedPanel != null) {
        migrateCtrlCombosIfNeeded(prefs, savedPanel)
    } else {
        // Fresh defaults already include common Ctrl combos.
        prefs.edit().putBoolean(PREF_CTRL_COMBOS_MIGRATED, true).apply()
        defaultPanelKeys() + loadLegacyCustomKeys(prefs)
    }
    return ExtraKeysLayout(topKeys = top, panelKeys = panel)
}

/**
 * Older installs saved a panel without ^C/^Z/…. Prepend the common combos once.
 */
private fun migrateCtrlCombosIfNeeded(
    prefs: SharedPreferences,
    panel: List<ExtraKey>
): List<ExtraKey> {
    if (prefs.getBoolean(PREF_CTRL_COMBOS_MIGRATED, false)) return panel
    prefs.edit().putBoolean(PREF_CTRL_COMBOS_MIGRATED, true).apply()
    val hasCombo = panel.any { it.keyName.matches(Regex("""\^[A-Za-z]""")) }
    if (hasCombo) return panel
    val combos = defaultPanelKeys().take(12) // first three groups of 4
    val merged = combos + panel
    // Persist so terminal + settings stay in sync without requiring Reset.
    saveKeyList(prefs, PREF_PANEL, merged)
    bumpRevision(prefs)
    return merged
}

fun saveExtraKeysLayout(prefs: SharedPreferences, layout: ExtraKeysLayout) {
    saveKeyList(prefs, PREF_TOP, layout.topKeys)
    saveKeyList(
        prefs,
        PREF_PANEL,
        layout.panelKeys.filter { it.id != ADD_KEY_ID && it.keyName != ADD_KEY_NAME }
    )
    bumpRevision(prefs)
}

fun resetExtraKeysLayout(prefs: SharedPreferences): ExtraKeysLayout {
    val layout = ExtraKeysLayout(defaultTopKeys(), defaultPanelKeys())
    prefs.edit()
        .remove(PREF_TOP)
        .remove(PREF_PANEL)
        .remove(PREF_CUSTOM_LEGACY)
        .putBoolean(PREF_CTRL_COMBOS_MIGRATED, true)
        .apply()
    saveExtraKeysLayout(prefs, layout)
    return layout
}

fun keysRevision(prefs: SharedPreferences): Long =
    prefs.getLong(PREF_REVISION, 0L)

private fun bumpRevision(prefs: SharedPreferences) {
    prefs.edit().putLong(PREF_REVISION, System.currentTimeMillis()).apply()
}

fun newCustomExtraKey(label: String, keyName: String): ExtraKey =
    ExtraKey(
        id = "custom_${UUID.randomUUID()}",
        label = label.take(4),
        keyName = keyName,
        isCustom = true
    )

fun defaultTopKeys(): List<ExtraKey> = listOf(
    ExtraKey("top_left", "◀", "LEFT", isArrow = true),
    ExtraKey("top_up", "▲", "UP", isArrow = true),
    ExtraKey("top_down", "▼", "DOWN", isArrow = true),
    ExtraKey("top_right", "▶", "RIGHT", isArrow = true),
    ExtraKey("top_shift", "SHIFT", "SHIFT", isToggle = true),
    ExtraKey("top_tab", "TAB", "TAB"),
    ExtraKey("top_enter", "ENTER", "ENTER"),
    ExtraKey("top_alt", "ALT", "ALT", isToggle = true),
    ExtraKey("top_ctrl", "CTRL", "CTRL", isToggle = true),
    ExtraKey("top_del", "DEL", "DEL"),
    ExtraKey("top_esc", "ESC", "ESC"),
    ExtraKey("top_settings", "", "SETTINGS_ACTION"),
    ExtraKey("top_snippet", "⚡", "SNIPPET_ACTION")
)

fun defaultPanelKeys(): List<ExtraKey> {
    fun k(id: String, label: String, name: String = label) =
        ExtraKey(id, label, name)

    // Groups of 4 for settings editing; terminal renders 8 per row.
    return listOf(
        // Common Ctrl combos (shell / readline)
        k("p_cc", "^C", "^C"), k("p_cz", "^Z", "^Z"), k("p_cd", "^D", "^D"), k("p_cl", "^L", "^L"),
        k("p_ca", "^A", "^A"), k("p_ce", "^E", "^E"), k("p_cu", "^U", "^U"), k("p_cw", "^W", "^W"),
        k("p_cr", "^R", "^R"), k("p_ck", "^K", "^K"), k("p_cy", "^Y", "^Y"), k("p_cv", "^V", "^V"),
        k("p_home", "HOME"), k("p_pgup", "PGUP"), k("p_pgdn", "PGDN"), k("p_end", "END"),
        k("p_ins", "INS"), k("p_bs", "\\", "\\"), k("p_q", "?", "?"), k("p_minus", "-", "-"),
        k("p_tilde", "~", "~"), k("p_at", "@", "@"), k("p_dollar", "$", "$"), k("p_star", "*", "*"),
        k("p_pipe", "|", "|"), k("p_colon", ":", ":"), k("p_semi", ";", ";"), k("p_bang", "!", "!"),
        k("p_caret", "^", "^"), k("p_pct", "%", "%"), k("p_eq", "=", "="), k("p_btick", "`", "`"),
        k("p_lt", "<", "<"), k("p_gt", ">", ">"), k("p_lparen", "(", "("), k("p_rparen", ")", ")"),
        k("p_lbrace", "{", "{"), k("p_rbrace", "}", "}"), k("p_lbrack", "[", "["), k("p_rbrack", "]", "]"),
        k("p_f1", "F1"), k("p_f2", "F2"), k("p_f3", "F3"), k("p_f4", "F4"),
        k("p_f5", "F5"), k("p_f6", "F6"), k("p_f7", "F7"), k("p_f8", "F8"),
        k("p_f9", "F9"), k("p_f10", "F10"), k("p_f11", "F11"), k("p_f12", "F12")
    )
}

/** Catalog of keys users can pick when editing the bar (settings + in-session). */
data class ExtraKeyCatalogEntry(
    val label: String,
    val keyName: String,
    val isArrow: Boolean = false,
    val isToggle: Boolean = false,
    val category: String
)

fun extraKeyCatalog(): List<ExtraKeyCatalogEntry> = buildList {
    fun add(category: String, label: String, name: String = label, arrow: Boolean = false, toggle: Boolean = false) {
        add(ExtraKeyCatalogEntry(label, name, arrow, toggle, category))
    }
    add("Modifiers", "CTRL", toggle = true)
    add("Modifiers", "ALT", toggle = true)
    add("Modifiers", "SHIFT", toggle = true)
    add("Modifiers", "ESC")
    add("Modifiers", "TAB")
    add("Modifiers", "ENTER")
    add("Modifiers", "DEL")
    add("Modifiers", "BKSP", "BKSP")

    // Common Ctrl combos — keyName is "^X" form; resolved to a control char on send.
    listOf(
        "C", "Z", "D", "L", "A", "E", "U", "W", "R", "K", "Y", "V",
        "X", "T", "B", "F", "N", "P", "G", "H", "O", "S", "Q"
    ).forEach { letter ->
        val label = "^$letter"
        add("Ctrl", label, label)
    }

    add("Arrows", "◀", "LEFT", arrow = true)
    add("Arrows", "▲", "UP", arrow = true)
    add("Arrows", "▼", "DOWN", arrow = true)
    add("Arrows", "▶", "RIGHT", arrow = true)
    add("Navigation", "HOME")
    add("Navigation", "END")
    add("Navigation", "PGUP")
    add("Navigation", "PGDN")
    add("Navigation", "INS")

    for (i in 1..12) add("Function", "F$i")

    val symbols = listOf(
        "\\", "?", "-", "~", "@", "$", "*", "|", ":", ";", "!",
        "^", "%", "=", "`", "<", ">", "(", ")", "{", "}", "[", "]",
        "/", "_", "+", "#", "&", "\"", "'", ",", "."
    )
    symbols.forEach { s -> add("Symbols", s, s) }

    add("Actions", "⚙", "SETTINGS_ACTION")
    add("Actions", "📁", "SFTP_ACTION")
    add("Actions", "***", "MACRO_ACTION")
}

fun ExtraKeyCatalogEntry.toExtraKey(idPrefix: String = "pick"): ExtraKey =
    ExtraKey(
        id = "${idPrefix}_${keyName}_${UUID.randomUUID().toString().take(8)}",
        label = when (keyName) {
            "SETTINGS_ACTION", "SFTP_ACTION" -> ""
            else -> label.take(4)
        },
        keyName = keyName,
        isArrow = isArrow,
        isToggle = isToggle
    )

private fun loadLegacyCustomKeys(prefs: SharedPreferences): List<ExtraKey> {
    val raw = prefs.getString(PREF_CUSTOM_LEGACY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val label = obj.optString("label").take(4)
                val keyName = obj.optString("keyName")
                if (label.isNotEmpty() && keyName.isNotEmpty()) {
                    add(
                        ExtraKey(
                            id = "custom_legacy_$i",
                            label = label,
                            keyName = keyName,
                            isCustom = true
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadKeyList(prefs: SharedPreferences, prefKey: String): List<ExtraKey>? {
    val raw = prefs.getString(prefKey, null) ?: return null
    return try {
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    ExtraKey(
                        id = obj.optString("id").ifEmpty { "k_$i" },
                        label = obj.optString("label"),
                        keyName = obj.optString("keyName"),
                        isArrow = obj.optBoolean("isArrow", false),
                        isToggle = obj.optBoolean("isToggle", false),
                        isCustom = obj.optBoolean("isCustom", false)
                    )
                )
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun saveKeyList(prefs: SharedPreferences, prefKey: String, keys: List<ExtraKey>) {
    val arr = JSONArray()
    for (key in keys) {
        arr.put(
            JSONObject().apply {
                put("id", key.id)
                put("label", key.label)
                put("keyName", key.keyName)
                put("isArrow", key.isArrow)
                put("isToggle", key.isToggle)
                put("isCustom", key.isCustom)
            }
        )
    }
    prefs.edit().putString(prefKey, arr.toString()).apply()
}

/**
 * Resolve user-entered combination text into bytes/chars to send to the PTY.
 * Supports Ctrl+X, Alt+x, Esc/Tab/Enter, and common escape sequences.
 */
fun resolveKeyPayload(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t

    Regex("""(?i)^(?:ctrl|control|ctl)\s*[+\- ]\s*([a-zA-Z])$""").matchEntire(t)?.let { m ->
        val ch = m.groupValues[1][0].lowercaseChar()
        return (ch - 'a' + 1).toChar().toString()
    }
    Regex("""(?i)^\^\s*([a-zA-Z])$""").matchEntire(t)?.let { m ->
        val ch = m.groupValues[1][0].lowercaseChar()
        return (ch - 'a' + 1).toChar().toString()
    }
    Regex("""(?i)^(?:alt|meta|option)\s*[+\- ]\s*(.)$""").matchEntire(t)?.let { m ->
        return "\u001b" + m.groupValues[1]
    }

    return when (t.lowercase()) {
        "esc", "escape" -> "\u001b"
        "enter", "return" -> "\r"
        "tab" -> "\t"
        "space" -> " "
        "bksp", "backspace" -> "\u007f"
        "del", "delete" -> "\u001b[3~"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "right" -> "\u001b[C"
        "left" -> "\u001b[D"
        "home" -> "\u001b[1~"
        "end" -> "\u001b[4~"
        else -> t
            .replace("\\e", "\u001b")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
