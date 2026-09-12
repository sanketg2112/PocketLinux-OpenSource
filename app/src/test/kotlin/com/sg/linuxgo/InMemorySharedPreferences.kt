package com.sg.linuxgo

import android.content.SharedPreferences

/**
 * JVM-friendly [SharedPreferences] for unit tests (no Android runtime / Robolectric).
 */
class InMemorySharedPreferences : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)

    override fun getString(key: String?, defValue: String?): String? {
        val v = map[key] ?: return defValue
        if (v !is String) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return v
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = map[key] ?: return defValues
        @Suppress("UNCHECKED_CAST")
        if (v !is Set<*>) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return (v as Set<String>).toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val v = map[key] ?: return defValue
        if (v !is Int) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return v
    }

    override fun getLong(key: String?, defValue: Long): Long {
        val v = map[key] ?: return defValue
        if (v !is Long) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return v
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        val v = map[key] ?: return defValue
        if (v !is Float) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return v
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val v = map[key] ?: return defValue
        if (v !is Boolean) throw ClassCastException("key=$key value is ${v.javaClass.simpleName}")
        return v
    }

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listeners.remove(listener)
    }

    /** Force a typed value (including wrong types for getSafe* tests). */
    fun putRaw(key: String, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
                pending.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            pending.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (k, v) ->
                if (v == null) map.remove(k) else map[k] = v
            }
            val changed = if (clearAll) map.keys.toList() else (removals + pending.keys).toList()
            clearAll = false
            pending.clear()
            removals.clear()
            changed.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
        }
    }
}
