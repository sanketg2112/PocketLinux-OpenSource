package com.sg.linuxgo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Host clipboard helpers for the container terminal (copy selection / paste into PTY).
 */
object TerminalClipboard {
    fun readText(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    fun writeText(context: Context, text: String, label: String = "Terminal") {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun pastePayload(context: Context, bracketed: Boolean): String {
        val raw = readText(context)
        if (raw.isEmpty()) return ""
        return TerminalKeyHandler.wrapPaste(raw, bracketed)
    }
}
