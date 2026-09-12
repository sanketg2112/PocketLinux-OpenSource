package com.sg.linuxgo.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Measures real session RAM for the container card.
 *
 * The old metric used only [Process.myPid] PSS (main UI process). The desktop
 * stack also lives in `:x11` / `:wayland`, keep-alive, and the proot guest tree
 * — separate PIDs under the same app UID. This sums PSS across that whole set.
 */
object SessionRamUsage {
    private const val TAG = "SessionRamUsage"
    private const val BATCH = 64

    /**
     * Total proportional set size (PSS) of every process owned by this app's UID,
     * in megabytes. Returns 0 if measurement fails.
     */
    fun measurePackagePssMb(context: Context): Int {
        val kb = measurePackagePssKb(context)
        if (kb <= 0L) return 0
        return (kb / 1024L).toInt().coerceAtLeast(1)
    }

    fun measurePackagePssKb(context: Context): Long {
        return try {
            val uid = Process.myUid()
            val pids = collectPidsForUid(uid)
            val usePids = if (pids.isEmpty()) intArrayOf(Process.myPid()) else pids
            sumPssKb(context, usePids)
        } catch (e: Exception) {
            Log.w(TAG, "measurePackagePssKb failed", e)
            0L
        }
    }

    /**
     * PIDs under [procRoot] whose real UID matches [uid].
     * Visible for unit tests with a fake /proc tree.
     */
    fun collectPidsForUid(uid: Int, procRoot: File = File("/proc")): IntArray {
        val out = ArrayList<Int>(64)
        val entries = procRoot.listFiles() ?: return intArrayOf()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (!entry.isDirectory) continue
            val realUid = readRealUid(File(entry, "status")) ?: continue
            if (realUid == uid) out.add(pid)
        }
        out.sort()
        return out.toIntArray()
    }

    /** Parse `Uid:` real-uid from a `/proc/[pid]/status` file. */
    fun readRealUid(statusFile: File): Int? {
        if (!statusFile.isFile) return null
        return try {
            statusFile.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("Uid:")) continue
                    val parts = line.split(Regex("\\s+"))
                    // Uid: real effective saved fs
                    return@useLines parts.getOrNull(1)?.toIntOrNull()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * PSS in kB from `/proc/[pid]/smaps_rollup` (preferred) or `status` VmRSS.
     * Visible for unit tests.
     */
    fun readPssKbFromProc(pid: Int, procRoot: File = File("/proc")): Long {
        val dir = File(procRoot, pid.toString())
        val rollup = File(dir, "smaps_rollup")
        parsePssKb(rollup)?.let { return it }
        // Fallback: RSS (not PSS) — better than zero when rollup is blocked.
        val status = File(dir, "status")
        return parseVmRssKb(status) ?: 0L
    }

    fun parsePssKb(smapsRollup: File): Long? {
        if (!smapsRollup.isFile) return null
        return try {
            smapsRollup.useLines { lines ->
                for (line in lines) {
                    // "Pss:        12345 kB" (also "Pss_Anon:", skip those)
                    if (!line.startsWith("Pss:")) continue
                    val parts = line.split(Regex("\\s+"))
                    return@useLines parts.getOrNull(1)?.toLongOrNull()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseVmRssKb(statusFile: File): Long? {
        if (!statusFile.isFile) return null
        return try {
            statusFile.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("VmRSS:")) continue
                    val parts = line.split(Regex("\\s+"))
                    return@useLines parts.getOrNull(1)?.toLongOrNull()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sumPssKb(context: Context, pids: IntArray): Long {
        if (pids.isEmpty()) return 0L
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return pids.sumOf { readPssKbFromProc(it) }

        var total = 0L
        var i = 0
        while (i < pids.size) {
            val end = minOf(i + BATCH, pids.size)
            val batch = pids.copyOfRange(i, end)
            try {
                val infos = am.getProcessMemoryInfo(batch)
                for (j in infos.indices) {
                    var pssKb = infos[j].totalPss.toLong()
                    if (pssKb <= 0L) {
                        pssKb = readPssKbFromProc(batch[j])
                    }
                    total += pssKb
                }
                // getProcessMemoryInfo can return a shorter array on some OEMs
                if (infos.size < batch.size) {
                    for (j in infos.size until batch.size) {
                        total += readPssKbFromProc(batch[j])
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getProcessMemoryInfo failed for batch@$i", e)
                for (pid in batch) {
                    total += readPssKbFromProc(pid)
                }
            }
            i = end
        }
        return total
    }
}
