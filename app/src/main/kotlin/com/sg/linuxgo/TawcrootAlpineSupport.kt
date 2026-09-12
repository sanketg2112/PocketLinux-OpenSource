package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Alpine + tawcroot helpers.
 *
 * tawcroot only accepts **directory** binds (open with O_DIRECTORY), so we cannot
 * bind host [libapk.so] as `/sbin/apk.static` the way classic proot does.
 * Instead, copy the host static apk into the guest rootfs (writable app data).
 */
object TawcrootAlpineSupport {
    private const val TAG = "TawcrootAlpine"

    fun isAlpineRootfs(rootfs: File): Boolean {
        return File(rootfs, "etc/alpine-release").isFile ||
            File(rootfs, "etc/apk/world").isFile ||
            (
                File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText()
                    ?.contains("ID=alpine", ignoreCase = true) == true
                )
    }

    /**
     * Stage host [libapk.so] at guest `/sbin/apk.static` (and a thin `/sbin/apk`
     * wrapper if missing). Safe to call every session; skips rewrite when size matches.
     *
     * @return absolute guest path of apk.static, or null if host binary missing.
     */
    fun stageApkStatic(context: Context, rootfs: File): String? {
        val src = File(context.applicationInfo.nativeLibraryDir, "libapk.so")
        if (!src.isFile) {
            Log.w(TAG, "libapk.so missing in nativeLibraryDir")
            return null
        }
        val sbin = File(rootfs, "sbin").apply { mkdirs() }
        val dest = File(sbin, "apk.static")
        try {
            val needCopy = !dest.isFile || dest.length() != src.length() ||
                dest.lastModified() < src.lastModified()
            if (needCopy) {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Staged apk.static (${src.length()} bytes) → ${dest.absolutePath}")
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            // Install scripts exec /sbin/apk.static; also provide /sbin/apk if absent.
            val apkShim = File(sbin, "apk")
            if (!apkShim.exists()) {
                apkShim.writeText(
                    "#!/bin/sh\n" +
                        "# PocketLinux: host-staged static apk (tawcroot cannot bind libapk.so)\n" +
                        "exec /sbin/apk.static \"\$@\"\n"
                )
                apkShim.setReadable(true, false)
                apkShim.setExecutable(true, false)
            }
            return dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "stageApkStatic failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Prepare Alpine rootfs for tawcroot session/setup: stage apk.static when needed.
     * No-op for non-Alpine or when not using tawcroot.
     */
    fun prepareAlpineForTawcroot(
        context: Context,
        rootfs: File,
        purpose: ProotBinary.Purpose = ProotBinary.Purpose.GENERAL
    ): Boolean {
        val runtime = try {
            ProotBinary.resolve(context, purpose)
        } catch (_: Exception) {
            return false
        }
        if (!runtime.useTawcroot) return false
        if (!isAlpineRootfs(rootfs)) return false
        return stageApkStatic(context, rootfs) != null
    }
}
