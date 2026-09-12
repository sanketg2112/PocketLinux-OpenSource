package com.sg.linuxgo

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.sg.linuxgo.util.SessionRamUsage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Stack

class StatsPoller(
    private val context: Context,
    private val containerManager: ContainerManager,
    private val containerAdapterProvider: () -> ContainerCardController?,
    private val activeContainerIdProvider: () -> String?,
    private val isAnySessionRunningProvider: () -> Boolean,
    private val updateSessionNotificationCall: (String, Int) -> Unit,
    private val clearSessionNotificationCall: () -> Unit,
    private val updateAdapterActiveStateCall: () -> Unit,
    private val runOnUiThreadCall: (Runnable) -> Unit
) {
    private var statsThread: Thread? = null
    @Volatile var isPollingStats = false
        private set
    /** When true, next poll remeasures storage for all installed containers. */
    @Volatile private var forceStorageRefresh = false
    /** Optional container id to prioritize on next storage pass. */
    @Volatile private var forceRefreshContainerId: String? = null

    fun startStatsPoller() {
        if (isPollingStats) return
        isPollingStats = true

        statsThread = Thread {
            val storageCache = mutableMapOf<String, Int>()
            var lastStorageCheckTime = 0L

            while (isPollingStats) {
                try {
                    val now = System.currentTimeMillis()
                    val forceAll = forceStorageRefresh
                    forceStorageRefresh = false
                    val forcedCid = forceRefreshContainerId
                    forceRefreshContainerId = null
                    val checkStorage = forceAll || now - lastStorageCheckTime > 30000 || storageCache.isEmpty()

                    val statFs = StatFs(context.filesDir.absolutePath)
                    val storageTotalMB = (statFs.totalBytes / (1024 * 1024)).toInt()
                    val storageFreeMB = (statFs.availableBytes / (1024 * 1024)).toInt()
                    val systemUsedStorageMB = storageTotalMB - storageFreeMB

                    val containers = containerManager.getContainers()
                    val activeContainerId = activeContainerIdProvider()
                    val containerAdapter = containerAdapterProvider()

                    for (container in containers) {
                        if (!container.isInstalled) continue
                        val cid = container.id

                        var storageUsedMB = storageCache[cid] ?: 0
                        // Always measure first-time / unknown containers; don't wait 30s.
                        val measureThis = checkStorage || cid !in storageCache || cid == forcedCid
                        if (measureThis) {
                            val rootfsPath = containerManager.getContainerRootfsPath(cid)
                            var sizeMB = -1
                            try {
                                val process = Runtime.getRuntime().exec(arrayOf("du", "-s", rootfsPath))
                                val reader = BufferedReader(InputStreamReader(process.inputStream))
                                val line = reader.readLine()
                                reader.close()
                                process.destroy()
                                if (line != null) {
                                    val parts = line.split(Regex("\\s+"))
                                    if (parts.isNotEmpty()) {
                                        val sizeKB = parts[0].toLongOrNull()
                                        if (sizeKB != null) {
                                            sizeMB = (sizeKB / 1024).toInt()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("StatsPoller", "Failed to run du on $rootfsPath", e)
                            }

                            if (sizeMB >= 0) {
                                storageUsedMB = sizeMB
                            } else {
                                val rootfsFile = File(rootfsPath)
                                var size = 0L
                                try {
                                    val stack = Stack<File>()
                                    stack.push(rootfsFile)
                                    while (stack.isNotEmpty() && isPollingStats) {
                                        val f = stack.pop()
                                        if (f.isDirectory) {
                                            f.listFiles()?.forEach { stack.push(it) }
                                        } else {
                                            size += f.length()
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                                storageUsedMB = (size / (1024 * 1024)).toInt()
                            }
                            storageCache[cid] = storageUsedMB
                        }

                        var ramUsedMB = 0
                        var ramTotalMB = 0
                        var systemUsedRamMB = 0
                        if (cid == activeContainerId) {
                            if (isAnySessionRunningProvider()) {
                                // Full app-UID PSS: main + :x11/:wayland + proot guest tree.
                                // (Main-process-only PSS under-counted desktop RAM by a large margin.)
                                ramUsedMB = SessionRamUsage.measurePackagePssMb(context)

                                val actManager =
                                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                val memInfo = ActivityManager.MemoryInfo()
                                actManager.getMemoryInfo(memInfo)
                                ramTotalMB = (memInfo.totalMem / (1024 * 1024)).toInt()
                                val ramFreeMB = (memInfo.availMem / (1024 * 1024)).toInt()
                                systemUsedRamMB = (ramTotalMB - ramFreeMB).coerceAtLeast(0)
                                // Ensure bar math stays sane if measurement > "used" snapshot.
                                if (ramUsedMB > systemUsedRamMB && systemUsedRamMB > 0) {
                                    systemUsedRamMB = ramUsedMB
                                }

                                updateSessionNotificationCall(cid, ramUsedMB)
                            } else {
                                clearSessionNotificationCall()
                            }
                        }

                        saveStatsToCache(cid, storageUsedMB, storageTotalMB, ramUsedMB, ramTotalMB, systemUsedStorageMB, systemUsedRamMB)
                        containerAdapter?.let { adapter ->
                            runOnUiThreadCall(Runnable {
                                adapter.updateStats(cid, storageUsedMB, storageTotalMB, ramUsedMB, ramTotalMB, systemUsedStorageMB, systemUsedRamMB)
                            })
                        }
                    }

                    if (checkStorage || forcedCid != null) {
                        lastStorageCheckTime = now
                    }

                    runOnUiThreadCall(Runnable {
                        updateAdapterActiveStateCall()
                    })

                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    // Wake for immediate refresh — keep looping unless stop was requested.
                    if (!isPollingStats) {
                        Log.d("StatsPoller", "Stats poller stopped")
                        break
                    }
                } catch (e: Exception) {
                    Log.e("StatsPoller", "Stats poller error", e)
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                        if (!isPollingStats) break
                    }
                }
            }
        }
        statsThread?.start()
    }

    fun stopStatsPoller() {
        isPollingStats = false
        statsThread?.interrupt()
        statsThread = null
    }

    /**
     * Ask the next poll cycle to remeasure storage immediately
     * (e.g. right after first install finishes).
     */
    fun requestImmediateStorageRefresh(containerId: String? = null) {
        forceStorageRefresh = true
        forceRefreshContainerId = containerId
        // Wake a sleeping poller thread so the bar appears without waiting ~3s.
        statsThread?.interrupt()
    }

    /**
     * Push device-level storage totals into the UI now (container size may be 0
     * until [requestImmediateStorageRefresh] finishes measuring rootfs).
     */
    fun seedSystemStorageForContainer(containerId: String) {
        try {
            val statFs = StatFs(context.filesDir.absolutePath)
            val storageTotalMB = (statFs.totalBytes / (1024 * 1024)).toInt().coerceAtLeast(1)
            val storageFreeMB = (statFs.availableBytes / (1024 * 1024)).toInt()
            val systemUsedStorageMB = (storageTotalMB - storageFreeMB).coerceAtLeast(0)
            val adapter = containerAdapterProvider()
            runOnUiThreadCall(Runnable {
                adapter?.updateStats(
                    containerId,
                    storageUsedMB = 0,
                    storageTotalMB = storageTotalMB,
                    ramUsedMB = 0,
                    ramTotalMB = 0,
                    systemUsedStorageMB = systemUsedStorageMB,
                    systemUsedRamMB = 0
                )
            })
            saveStatsToCache(containerId, 0, storageTotalMB, 0, 0, systemUsedStorageMB, 0)
        } catch (e: Exception) {
            Log.e("StatsPoller", "seedSystemStorageForContainer failed", e)
        }
    }

    fun saveStatsToCache(containerId: String, storageUsedMB: Int, storageTotalMB: Int, ramUsedMB: Int, ramTotalMB: Int, systemUsedStorageMB: Int, systemUsedRamMB: Int) {
        val prefs = context.getSharedPreferences("container_stats_cache", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("${containerId}_storage_used", storageUsedMB)
            .putInt("${containerId}_storage_total", storageTotalMB)
            .putInt("${containerId}_ram_used", ramUsedMB)
            .putInt("${containerId}_ram_total", ramTotalMB)
            .putInt("${containerId}_system_storage_used", systemUsedStorageMB)
            .putInt("${containerId}_system_ram_used", systemUsedRamMB)
            .apply()
    }

    fun loadCachedStats() {
        val prefs = context.getSharedPreferences("container_stats_cache", Context.MODE_PRIVATE)
        val containers = containerManager.getContainers()
        val containerAdapter = containerAdapterProvider() ?: return
        for (container in containers) {
            val cid = container.id
            if (prefs.contains("${cid}_storage_used")) {
                val storageUsedMB = prefs.getInt("${cid}_storage_used", 0)
                val storageTotalMB = prefs.getInt("${cid}_storage_total", 0)
                val ramUsedMB = prefs.getInt("${cid}_ram_used", 0)
                val ramTotalMB = prefs.getInt("${cid}_ram_total", 0)
                val systemUsedStorageMB = prefs.getInt("${cid}_system_storage_used", 0)
                val systemUsedRamMB = prefs.getInt("${cid}_system_ram_used", 0)
                containerAdapter.updateStats(cid, storageUsedMB, storageTotalMB, ramUsedMB, ramTotalMB, systemUsedStorageMB, systemUsedRamMB)
            }
        }
    }

    fun removeStatsFromCache(containerId: String) {
        val prefs = context.getSharedPreferences("container_stats_cache", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${containerId}_storage_used")
            .remove("${containerId}_storage_total")
            .remove("${containerId}_ram_used")
            .remove("${containerId}_ram_total")
            .remove("${containerId}_system_storage_used")
            .remove("${containerId}_system_ram_used")
            .apply()
    }
}
