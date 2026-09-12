package com.sg.linuxgo

import android.widget.TextView
import com.google.android.material.tabs.TabLayout

interface TerminalViewDelegate {
    fun getTerminalOutputView(): TextView
    fun getTerminalScrollView(): android.widget.ScrollView
    fun getTerminalCursorView(): android.view.View
    fun getTabLayoutTerminal(): TabLayout
    fun getSessionThemes(): MutableMap<String, String>
    fun runOnUiThread(action: () -> Unit)
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun switchToHomeTab()
    fun updateSessionNotification(containerId: String, ram: Int)
    /** Bump Compose observation so TerminalScreen re-reads [TerminalSession.renderedState]. */
    fun notifyTerminalOutputChanged()
}
