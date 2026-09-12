package com.sg.linuxgo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages multiple Linux container installations.
 *
 * Each container lives in its own directory under `filesDir/containers/<id>/rootfs`.
 * Container metadata is persisted in SharedPreferences as JSON.
 *
 * On first launch after upgrading from the legacy single-rootfs layout,
 * the existing installation is automatically migrated into the container system.
 */
class ContainerManager(private val context: Context) {
    companion object {
        private const val TAG = "ContainerManager"
        private const val PREFS_NAME = "linux_go_containers"
        private const val KEY_CONTAINERS = "containers"
        private const val MAX_CONTAINERS = 20

        /**
         * Best-effort recursive delete that never throws [AssertionError] from
         * Kotlin's file walk. Returns true if [root] no longer exists.
         */
        @JvmStatic
        fun safeDeleteRecursively(root: File): Boolean {
            if (!root.exists()) return true
            return try {
                deleteTreeSafely(root)
                !root.exists()
            } catch (t: Throwable) {
                Log.e(TAG, "safeDeleteRecursively failed: ${root.absolutePath}", t)
                !root.exists()
            }
        }

        private fun deleteTreeSafely(file: File): Boolean {
            if (file.isDirectory) {
                val children = try {
                    file.listFiles()
                } catch (_: Throwable) {
                    null
                }
                if (children != null) {
                    for (child in children) {
                        deleteTreeSafely(child)
                    }
                }
            }
            return try {
                file.delete() || !file.exists()
            } catch (_: Throwable) {
                !file.exists()
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── CRUD ─────────────────────────────────────────────────────────────────

    fun getContainers(): List<ContainerConfig> {
        val raw = prefs.getString(KEY_CONTAINERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ContainerConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse containers", e)
            emptyList()
        }
    }

    fun getContainer(id: String): ContainerConfig? {
        return getContainers().find { it.id == id }
    }

    fun addContainer(config: ContainerConfig): Boolean {
        val list = getContainers().toMutableList()
        if (list.size >= MAX_CONTAINERS) {
            Log.w(TAG, "Max containers ($MAX_CONTAINERS) reached")
            return false
        }
        list.add(config)
        saveContainers(list)

        // Pre-create the container directory
        val dir = File(getContainerRootfsPath(config.id))
        if (!dir.exists()) dir.mkdirs()
        return true
    }

    fun updateContainer(config: ContainerConfig) {
        val list = getContainers().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            list[idx] = config
            saveContainers(list)
        }
    }

    /**
     * Mark a container as fully installed and persist immediately.
     * Also accepts disk evidence (root/launch.sh) so a completed setup that
     * missed the SUCCESS path still becomes READY on the home card.
     *
     * @return the updated container list after save (never stale apply()-raced data)
     */
    fun markInstalled(id: String): List<ContainerConfig> {
        val list = getContainers().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "markInstalled: container $id not found")
            return list
        }
        val current = list[idx]
        if (!current.isInstalled) {
            list[idx] = current.copy(isInstalled = true)
            saveContainers(list)
            Log.i(TAG, "markInstalled: $id → isInstalled=true")
        }
        return list
    }

    /**
     * Scan rootfs for launch.sh and flip isInstalled for any ready containers
     * whose prefs flag was never set (e.g. SUCCESS broadcast missed / apply race).
     * @return true if any container was updated
     */
    fun reconcileInstalledFlags(): Boolean {
        val list = getContainers().toMutableList()
        var changed = false
        for (i in list.indices) {
            val c = list[i]
            if (!c.isInstalled && isContainerInstalled(c.id)) {
                list[i] = c.copy(isInstalled = true)
                changed = true
                Log.i(TAG, "reconcileInstalledFlags: ${c.id} has launch.sh → marked installed")
            }
        }
        if (changed) saveContainers(list)
        return changed
    }

    fun removeContainer(id: String) {
        val list = getContainers().toMutableList()
        list.removeAll { it.id == id }
        saveContainers(list)

        // Delete the rootfs directory in a background thread.
        // Never use Kotlin's File.deleteRecursively() here: it can throw AssertionError
        // ("rootDir must be verified to be directory beforehand") when a path is not a
        // directory or races with a concurrent delete — AssertionError is an Error, so
        // catch (Exception) does not stop a process-killing uncaught exception.
        Thread {
            try {
                val containerDir = File(context.filesDir, "containers/$id")
                if (safeDeleteRecursively(containerDir)) {
                    Log.d(TAG, "Deleted container directory: ${containerDir.absolutePath}")
                } else {
                    Log.w(TAG, "Partial delete of container directory: ${containerDir.absolutePath}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to delete container $id", t)
            }
        }.start()
    }

    fun canAddMore(): Boolean = getContainers().size < MAX_CONTAINERS

    // ── Paths ────────────────────────────────────────────────────────────────

    /** Root filesystem path for a container */
    fun getContainerRootfsPath(id: String): String {
        return File(context.filesDir, "containers/$id/rootfs").absolutePath
    }

    /** Base directory for a container (parent of rootfs) */
    fun getContainerBasePath(id: String): String {
        return File(context.filesDir, "containers/$id").absolutePath
    }

    /** Shared cache directory for downloaded rootfs tarballs.
     *  Allows cross-container reuse: e.g. Alpine+XFCE and Alpine+MATE
     *  share the same downloaded rootfs.tar.xz */
    fun getRootfsCacheDir(): File {
        val dir = File(context.filesDir, "rootfs_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Get the cached rootfs tarball path for a distro (null if not cached) */
    fun getCachedRootfs(distro: String): File? {
        val cacheDir = getRootfsCacheDir()
        // Check for both .tar.xz and .tar.gz
        val xz = File(cacheDir, "${distro}_rootfs.tar.xz")
        val gz = File(cacheDir, "${distro}_rootfs.tar.gz")
        
        val extractionUtils = com.sg.linuxgo.util.ExtractionUtils(context)
        
        if (xz.exists()) {
            if (xz.length() > 0 && extractionUtils.isArchiveValid(xz)) {
                return xz
            } else {
                Log.w("ContainerManager", "Deleting corrupted/incomplete cached xz rootfs: ${xz.absolutePath}")
                xz.delete()
            }
        }
        
        if (gz.exists()) {
            if (gz.length() > 0 && extractionUtils.isArchiveValid(gz)) {
                return gz
            } else {
                Log.w("ContainerManager", "Deleting corrupted/incomplete cached gz rootfs: ${gz.absolutePath}")
                gz.delete()
            }
        }
        
        return null
    }

    /** Get the path where a rootfs tarball should be cached for a distro */
    fun getRootfsCachePath(distro: String, isXz: Boolean): File {
        val ext = if (isXz) "tar.xz" else "tar.gz"
        return File(getRootfsCacheDir(), "${distro}_rootfs.$ext")
    }

    /** Check if a container's rootfs is fully installed (has launch.sh / launcher) */
    fun isContainerInstalled(id: String): Boolean {
        val rootfs = File(getContainerRootfsPath(id))
        if (!rootfs.isDirectory) return false
        // Primary: Bootstrap writes root/launch.sh after the install script finishes.
        if (File(rootfs, "root/launch.sh").exists()) return true
        // Fallback signals used if launch.sh write was skipped/failed after a full install.
        if (File(rootfs, "usr/local/bin/pocketlinux-launch").exists()) return true
        // Arch/Debian/Alpine: guest shell present AND a common DE session binary installed.
        // Alpine bin/sh → /bin/busybox (guest-absolute); host File.exists() is false.
        val hasShell = com.sg.linuxgo.util.TarHardlinkSafeExtract.hasGuestShell(rootfs)
        val hasDesktop =
            File(rootfs, "usr/bin/xfce4-session").exists() ||
            File(rootfs, "usr/bin/mate-session").exists() ||
            File(rootfs, "usr/bin/startplasma-x11").exists() ||
            File(rootfs, "usr/bin/lxqt-session").exists() ||
            File(rootfs, "usr/bin/startxfce4").exists()
        return hasShell && hasDesktop
    }

    /** Check if any container is installed and ready to use */
    fun isAnyContainerInstalled(): Boolean {
        return getContainers().any { it.isInstalled && isContainerInstalled(it.id) }
    }

    // ── Legacy Migration ─────────────────────────────────────────────────────

    /**
     * Migrate the legacy single-rootfs installation (`filesDir/rootfs`) into
     * the new container system. Called once on app startup.
     *
     * If `filesDir/rootfs` exists AND no containers are registered, we:
     * 1. Generate a new container ID
     * 2. Move `rootfs` → `containers/<id>/rootfs`
     * 3. Register the container with default config
     */
    fun migrateLegacyIfNeeded() {
        val legacyRootfs = File(context.filesDir, "rootfs")
        val legacyLaunch = File(legacyRootfs, "root/launch.sh")

        // Only migrate if legacy rootfs exists AND we have no containers yet
        if (!legacyRootfs.exists() || getContainers().isNotEmpty()) return

        Log.i(TAG, "Detected legacy rootfs — migrating to container system")

        val id = UUID.randomUUID().toString().take(8)
        val containerDir = File(context.filesDir, "containers/$id")
        containerDir.mkdirs()

        try {
            // Move the rootfs directory
            val newRootfs = File(containerDir, "rootfs")
            val success = legacyRootfs.renameTo(newRootfs)

            if (success) {
                // Read saved settings to detect the distro/DE from the old installation
                val settingsPrefs = context.getSharedPreferences("pocket_linux_settings", Context.MODE_PRIVATE)
                val customName = settingsPrefs.getString("setup_name", "") ?: ""

                val config = ContainerConfig(
                    id = id,
                    distro = "alpine",   // Legacy was always Alpine
                    de = "xfce4",        // Legacy was always XFCE
                    wm = "none",
                    name = customName.ifEmpty { "Alpine / XFCE" },
                    username = "PocketLinux",
                    software = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    isInstalled = legacyLaunch.exists() || File(newRootfs, "root/launch.sh").exists()
                )
                addContainer(config)
                Log.i(TAG, "Legacy rootfs migrated to container $id")
            } else {
                Log.e(TAG, "Failed to rename legacy rootfs to container directory")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy migration failed", e)
        }
    }

    // ── Create a new container config ────────────────────────────────────────

    fun createNewContainer(
        distro: String,
        de: String,
        wm: String,
        software: List<String>,
        username: String = "PocketLinux",
        customName: String? = null,
        installRecommends: Boolean = false,
        guiMode: String = "x11"
    ): ContainerConfig {
        val id = UUID.randomUUID().toString().take(8)
        val name = customName ?: "${ContainerConfig.distroLabel(distro)} / ${ContainerConfig.deLabel(de)}"
        return ContainerConfig(
            id = id,
            distro = distro,
            de = de,
            wm = wm,
            name = name,
            username = username,
            software = software,
            createdAt = System.currentTimeMillis(),
            isInstalled = false,
            installRecommends = installRecommends,
            guiMode = guiMode
        )
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun saveContainers(list: List<ContainerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        // commit() so immediate getContainers() after update/markInstalled sees
        // the new isInstalled flag (apply() is async and caused READY-button races).
        prefs.edit().putString(KEY_CONTAINERS, arr.toString()).commit()
    }
}
