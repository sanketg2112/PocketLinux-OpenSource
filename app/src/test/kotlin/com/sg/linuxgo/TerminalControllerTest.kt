package com.sg.linuxgo

import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TerminalControllerTest {

    private class StubSharedPreferences : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class DummyContext : ContextWrapper(null) {
        override fun getCacheDir(): File = File("/tmp")
        override fun getFilesDir(): File = File("/tmp")
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return StubSharedPreferences()
        }
    }

    @Test
    fun testTerminalControllerInitialization() {
        val context = DummyContext()
        val containerManager = ContainerManager(context)
        val bootstrap: Bootstrap? = null
        val terminalBridge = TerminalBridge()
        val terminalSessionManager = TerminalSessionManager()

        val viewDelegate = object : TerminalViewDelegate {
            override fun getTerminalOutputView(): TextView = throw UnsupportedOperationException()
            override fun getTerminalScrollView(): ScrollView = throw UnsupportedOperationException()
            override fun getTerminalCursorView(): android.view.View = throw UnsupportedOperationException()
            override fun getTabLayoutTerminal(): TabLayout = throw UnsupportedOperationException()
            override fun getSessionThemes(): MutableMap<String, String> = mutableMapOf()
            override fun runOnUiThread(action: () -> Unit) {
                action()
            }
            override fun acquireWakeLock() {}
            override fun releaseWakeLock() {}
            override fun switchToHomeTab() {}
            override fun updateSessionNotification(containerId: String, ram: Int) {}
            override fun notifyTerminalOutputChanged() {}
        }

        val controller = TerminalController(
            context = context,
            containerManager = containerManager,
            bootstrap = bootstrap,
            terminalBridge = terminalBridge,
            terminalSessionManager = terminalSessionManager,
            activeContainerIdProvider = { null },
            onSessionSelected = {},
            viewDelegate = viewDelegate
        )

        assertTrue(controller.terminalSessions.isEmpty())
        assertEquals(-1, controller.activeSessionIndex)
    }
}
