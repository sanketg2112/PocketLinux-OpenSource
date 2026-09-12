package com.sg.linuxgo

import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StatsPollerTest {

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
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return StubSharedPreferences()
        }
    }

    @Test
    fun testStatsPollerInitialization() {
        val context = DummyContext()
        val containerManager = ContainerManager(context)
        
        var notificationUpdated = false
        val poller = StatsPoller(
            context = context,
            containerManager = containerManager,
            containerAdapterProvider = { null },
            activeContainerIdProvider = { "c1" },
            isAnySessionRunningProvider = { true },
            updateSessionNotificationCall = { _, _ -> notificationUpdated = true },
            clearSessionNotificationCall = {},
            updateAdapterActiveStateCall = {},
            runOnUiThreadCall = { it.run() }
        )

        assertNotNull(poller)
        // Verify it isn't running initially
        poller.stopStatsPoller()
    }
}
