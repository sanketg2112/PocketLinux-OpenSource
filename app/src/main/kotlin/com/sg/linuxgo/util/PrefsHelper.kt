package com.sg.linuxgo.util

import android.content.SharedPreferences
import android.util.Log

fun SharedPreferences.getSafeFloat(key: String, defaultValue: Float): Float {
    try {
        return this.getFloat(key, defaultValue)
    } catch (e: ClassCastException) {
        var floatVal = defaultValue
        try {
            val value = this.all[key]
            if (value is Number) {
                floatVal = value.toFloat()
            } else if (value is String) {
                floatVal = value.toFloatOrNull() ?: defaultValue
            }
            this.edit().putFloat(key, floatVal).apply()
        } catch (ex: Exception) {
            Log.e("PrefsHelper", "Error converting and saving safe float for key: $key", ex)
        }
        return floatVal
    }
}

fun SharedPreferences.getSafeBoolean(key: String, defaultValue: Boolean): Boolean {
    try {
        return this.getBoolean(key, defaultValue)
    } catch (e: ClassCastException) {
        var boolVal = defaultValue
        try {
            val value = this.all[key]
            if (value is Boolean) {
                boolVal = value
            } else if (value is Number) {
                boolVal = value.toInt() != 0
            } else if (value is String) {
                boolVal = value.toBoolean()
            }
            this.edit().putBoolean(key, boolVal).apply()
        } catch (ex: Exception) {
            Log.e("PrefsHelper", "Error converting and saving safe boolean for key: $key", ex)
        }
        return boolVal
    }
}

fun SharedPreferences.getSafeString(key: String, defaultValue: String?): String? {
    try {
        return this.getString(key, defaultValue)
    } catch (e: ClassCastException) {
        var strVal = defaultValue
        try {
            val value = this.all[key]
            strVal = value?.toString() ?: defaultValue
            this.edit().putString(key, strVal).apply()
        } catch (ex: Exception) {
            Log.e("PrefsHelper", "Error converting and saving safe string for key: $key", ex)
        }
        return strVal
    }
}
