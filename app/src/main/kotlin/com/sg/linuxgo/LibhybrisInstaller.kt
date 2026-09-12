package com.sg.linuxgo

import android.content.Context
import android.util.Log
import com.sg.linuxgo.util.TarHardlinkSafeExtract
import java.io.File
import java.nio.file.Files

/**
 * Extract the APK-bundled libhybris tree and stage it for every glibc rootfs.
 *
 * Host extract: [filesDir]/libhybris  (bound at /usr/lib/hybris)
 * Per-rootfs copies: glvnd vendor JSON, linker config, override profile.
 * The .so tree is bound (not copied) so one extract serves every container.
 */
object LibhybrisInstaller {
    private const val TAG = "LibhybrisInstaller"
    private const val PROFILE_NAME = "zz-pocketlinux-hybris.sh"

    fun ensureExtracted(context: Context): Boolean {
        val dest = LibhybrisRuntime.hostLibDir(context)
        val stamp = File(dest, ".version")
        if (dest.isDirectory &&
            File(dest, "libhybris-common.so").exists() &&
            stamp.takeIf { it.isFile }?.readText()?.trim() == LibhybrisRuntime.VERSION_STAMP
        ) {
            return true
        }
        return try {
            val tmp = File(context.cacheDir, "libhybris-extract")
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            context.assets.open(LibhybrisRuntime.ASSET_TAR).use { input ->
                TarHardlinkSafeExtract.extractStream(input, tmp)
            }
            val staged = dest.parentFile
            staged?.mkdirs()
            val swap = File(staged, "libhybris.new")
            if (swap.exists()) swap.deleteRecursively()
            if (!tmp.renameTo(swap)) {
                copyTree(tmp, swap)
                tmp.deleteRecursively()
            }
            if (dest.exists()) dest.deleteRecursively()
            if (!swap.renameTo(dest)) {
                copyTree(swap, dest)
                swap.deleteRecursively()
            }
            File(dest, ".version").writeText(LibhybrisRuntime.VERSION_STAMP + "\n")
            dest.isDirectory && File(dest, "libhybris-common.so").exists()
        } catch (e: Exception) {
            Log.w(TAG, "ensureExtracted failed: ${e.message}")
            false
        }
    }

    /**
     * Stage hybris for [rootfs]. Returns a short status for logs.
     */
    fun prepare(context: Context, rootfs: File): String {
        if (!LibhybrisRuntime.isEnabled(context)) {
            removeGuestProfile(rootfs)
            return "off"
        }
        if (LibhybrisRuntime.isMuslRootfs(rootfs)) {
            removeGuestProfile(rootfs)
            return "skipped-musl"
        }
        if (!ensureExtracted(context)) {
            removeGuestProfile(rootfs)
            return "missing-asset"
        }
        writeLinkerConfig(context, rootfs)
        writeGlvndVendor(context, rootfs)
        writeGuestProfile(rootfs)
        ensureGuestMountPoints(rootfs)
        return "ready"
    }

    fun removeGuestProfile(rootfs: File) {
        try {
            File(rootfs, "etc/profile.d/$PROFILE_NAME").delete()
        } catch (_: Exception) {
        }
    }

    internal fun writeGuestProfile(rootfs: File) {
        val dir = File(rootfs, "etc/profile.d").apply { mkdirs() }
        File(dir, PROFILE_NAME).writeText(guestProfileText())
        File(dir, PROFILE_NAME).setReadable(true, false)
    }

    internal fun guestProfileText(): String = """
        # PocketLinux experimental libhybris (stock Android GPU).
        # Sorts after pocketlinux-gpu.sh so Mesa/Turnip/Zink env is cleared.
        export LD_LIBRARY_PATH="${LibhybrisRuntime.GUEST_GL_SHIMS_DIR}:${LibhybrisRuntime.GUEST_LIB_DIR}${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        export HYBRIS_EGLPLATFORM=wayland
        export EGL_PLATFORM=wayland
        export ANDROID_ROOT=/system
        export POCKETLINUX_WAYLAND_GFX=hybris
        export POCKETLINUX_GPU_MODE=hybris
        export POCKETLINUX_LIBHYBRIS=1
        unset GDK_GL
        unset LIBGL_ALWAYS_SOFTWARE
        unset GALLIUM_DRIVER
        unset MESA_LOADER_DRIVER_OVERRIDE
        unset LIBGL_DRIVERS_PATH
        unset ZINK_DESCRIPTORS
        unset ZINK_DEBUG
        unset TU_DEBUG
        unset VK_ICD_FILENAMES
        unset MESA_VK_WSI_PRESENT_MODE
        unset MESA_EXTENSION_OVERRIDE
    """.trimIndent() + "\n"

    internal fun writeGlvndVendor(context: Context, rootfs: File) {
        val host = File(LibhybrisRuntime.hostGlvndDir(context), "00_libhybris.json")
        host.parentFile?.mkdirs()
        val json = LibhybrisRuntime.glvndVendorJson()
        if (!host.isFile || host.readText() != json) {
            host.writeText(json)
        }
        val destDir = File(rootfs, LibhybrisRuntime.GUEST_GLVND_DIR.removePrefix("/"))
        destDir.mkdirs()
        val dest = File(destDir, "00_libhybris.json")
        if (!dest.isFile || dest.length() != host.length()) {
            host.copyTo(dest, overwrite = true)
        }
        dest.setReadable(true, false)
    }

    internal fun writeLinkerConfig(context: Context, rootfs: File) {
        File(rootfs, "linkerconfig").delete()
        val src = File("/linkerconfig/ld.config.txt")
        val destDir = File(rootfs, LibhybrisRuntime.GUEST_CONFIG_DIR.removePrefix("/"))
        destDir.mkdirs()
        val dest = File(destDir, "ld.config.txt")
        if (src.isFile && src.length() > 0L) {
            if (dest.length() != src.length() || dest.lastModified() != src.lastModified()) {
                src.copyTo(dest, overwrite = true)
                dest.setLastModified(src.lastModified())
            }
        } else {
            // Unit tests / pre-Android 11: keep an empty readable placeholder.
            if (!dest.exists()) dest.writeText("")
        }
        dest.setReadable(true, false)
        val hostCfg = File(LibhybrisRuntime.hostConfigDir(context), "ld.config.txt")
        hostCfg.parentFile?.mkdirs()
        if (src.isFile && src.length() > 0L && hostCfg.length() != src.length()) {
            src.copyTo(hostCfg, overwrite = true)
        }
    }

    private fun ensureGuestMountPoints(rootfs: File) {
        File(rootfs, LibhybrisRuntime.GUEST_LIB_DIR.removePrefix("/")).mkdirs()
        for (dir in LibhybrisRuntime.ANDROID_BIND_DIRS) {
            File(rootfs, dir.removePrefix("/")).mkdirs()
        }
    }

    internal fun copyTree(from: File, to: File) {
        if (!from.exists()) return
        to.mkdirs()
        from.walkTopDown().forEach { src ->
            val rel = src.relativeTo(from).path
            if (rel.isEmpty()) return@forEach
            val dest = File(to, rel)
            val path = src.toPath()
            when {
                Files.isSymbolicLink(path) -> {
                    dest.parentFile?.mkdirs()
                    if (dest.exists()) dest.delete()
                    val target = Files.readSymbolicLink(path)
                    Files.createSymbolicLink(dest.toPath(), target)
                }
                src.isDirectory -> dest.mkdirs()
                src.isFile -> {
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                }
            }
        }
    }
}
