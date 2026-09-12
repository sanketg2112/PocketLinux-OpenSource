package com.sg.linuxgo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.core.content.ContextCompat

/**
 * Phone-storage bind into Linux is a product choice, not a PRoot requirement.
 * Bind /sdcard only when the user asked and Android storage access is granted.
 */
object LinuxIsolation {
    const val PREF_BIND_PHONE_STORAGE = "bind_phone_storage"

    fun hasExplicitBindChoice(context: Context): Boolean {
        return prefs(context).contains(PREF_BIND_PHONE_STORAGE)
    }

    fun isBindPrefEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(PREF_BIND_PHONE_STORAGE)) {
            return p.getBoolean(PREF_BIND_PHONE_STORAGE, false)
        }
        // Legacy installs that already granted All-files kept the old always-bind behavior.
        return hasStorageAccess(context)
    }

    fun setBindPhoneStorage(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_BIND_PHONE_STORAGE, enabled).apply()
    }

    fun hasStorageAccess(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    /** True when Linux should see phone shared storage at /sdcard. */
    fun shouldBindPhoneStorage(context: Context): Boolean {
        return isBindPrefEnabled(context) && hasStorageAccess(context)
    }

    /** PRoot/tawcroot bind spec, or null when the mount is off. */
    fun phoneStorageBindSpec(context: Context): String? {
        if (!shouldBindPhoneStorage(context)) return null
        return "/sdcard:/sdcard"
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
}
