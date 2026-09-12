package com.sg.linuxgo

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Experimental libhybris (wmww fork / tawc packaging): glibc guests load the
 * phone's stock Android GPU drivers instead of Mesa/Turnip/Zink.
 *
 * Wayland-only graphics backend (Experimental → Graphics backend = libhybris).
 * Independent of the container [gpu_driver_mode] picker. Alpine/musl is rejected.
 */
object LibhybrisRuntime {
    const val PREF_ENABLED = "enableLibhybris"
    const val GUEST_LIB_DIR = "/usr/lib/hybris"
    const val GUEST_GL_SHIMS_DIR = "$GUEST_LIB_DIR/gl-shims"
    const val GUEST_PLUGIN_DIR = "$GUEST_LIB_DIR/libhybris"
    const val GUEST_CONFIG_DIR = "/usr/lib/hybris-config"
    const val GUEST_GLVND_DIR = "/usr/share/glvnd/egl_vendor.d"
    const val ASSET_TAR = "libhybris/arm64-v8a.tar"
    const val VERSION_STAMP = "tawc-v1"

    /** Android partitions libhybris dlopens for vendor GLES/Vulkan. */
    val ANDROID_BIND_DIRS: List<String> = listOf(
        "/apex",
        "/vendor",
        "/system",
        "/system_ext",
        "/odm",
        "/product",
    )

    /** Mesa keys that must not shadow hybris when the toggle is on. */
    val MESA_ENV_KEYS: Set<String> = setOf(
        "MESA_LOADER_DRIVER_OVERRIDE",
        "GALLIUM_DRIVER",
        "LIBGL_ALWAYS_SOFTWARE",
        "LIBGL_DRIVERS_PATH",
        "ZINK_DESCRIPTORS",
        "ZINK_DEBUG",
        "TU_DEBUG",
        "VK_ICD_FILENAMES",
        "MESA_VK_WSI_PRESENT_MODE",
        "MESA_EXTENSION_OVERRIDE",
        "MESA_SHADER_CACHE_DISABLE",
        "MESA_SHADER_CACHE_MAX_SIZE",
    )

    fun isPrefEnabled(context: Context): Boolean {
        return TawcWaylandCompat.graphicsBackend(context) == TawcWaylandCompat.GFX_HYBRIS
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        TawcWaylandCompat.setGraphicsBackend(
            context,
            if (enabled) TawcWaylandCompat.GFX_HYBRIS else TawcWaylandCompat.GFX_MESA
        )
    }

    /** Wayland experimental + hybris backend. X11 always false. */
    fun isEnabled(context: Context): Boolean {
        return FeatureGates.isLibhybrisAllowed() &&
            FeatureGates.isWaylandEnabled(context) &&
            isPrefEnabled(context)
    }

    fun isMuslRootfs(rootfs: File): Boolean {
        if (TawcrootAlpineSupport.isAlpineRootfs(rootfs)) return true
        val markers = listOf(
            "lib/ld-musl-aarch64.so.1",
            "lib/ld-musl-x86_64.so.1",
            "usr/lib/libc.musl-aarch64.so.1",
        )
        return markers.any { File(rootfs, it).exists() }
    }

    fun isGlibcRootfs(rootfs: File): Boolean {
        return File(rootfs, "lib/ld-linux-aarch64.so.1").exists() ||
            File(rootfs, "lib64/ld-linux-aarch64.so.1").exists() ||
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1").exists() ||
            File(rootfs, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").exists()
    }

    fun hostLibDir(context: Context): File = File(context.filesDir, "libhybris")

    fun hostConfigDir(context: Context): File = File(context.filesDir, "hybris-config")

    fun hostGlvndDir(context: Context): File = File(context.filesDir, "libhybris-glvnd")

    /**
     * Apply guest env + android/hybris binds for this rootfs.
     * False on musl, when the toggle is off, or when the asset is missing.
     */
    fun shouldApply(context: Context, rootfs: File): Boolean {
        if (!isEnabled(context)) return false
        if (!rootfs.isDirectory) return false
        if (isMuslRootfs(rootfs)) return false
        return true
    }

    fun guestEnvPairs(): Map<String, String> = linkedMapOf(
        "LD_LIBRARY_PATH" to "$GUEST_GL_SHIMS_DIR:$GUEST_LIB_DIR",
        "HYBRIS_EGLPLATFORM" to "wayland",
        "EGL_PLATFORM" to "wayland",
        "ANDROID_ROOT" to "/system",
        "POCKETLINUX_WAYLAND_GFX" to TawcWaylandCompat.GFX_HYBRIS,
        "POCKETLINUX_GPU_MODE" to TawcWaylandCompat.GFX_HYBRIS,
        "POCKETLINUX_LIBHYBRIS" to "1",
    )

    /** Merge so hybris wins over Mesa/Turnip/Zink pairs. */
    fun mergeGuestEnv(base: Map<String, String>): Map<String, String> {
        val out = base.toMutableMap()
        MESA_ENV_KEYS.forEach { out.remove(it) }
        out.putAll(guestEnvPairs())
        return out
    }

    /**
     * Host→guest directory binds (hybris tree + Android partitions).
     * Only existing host directories are returned (tawcroot is dir-only).
     */
    fun directoryBinds(context: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(ANDROID_BIND_DIRS.size + 2)
        for (dir in ANDROID_BIND_DIRS) {
            if (File(dir).isDirectory) out += dir to dir
        }
        val hybris = hostLibDir(context)
        if (hybris.isDirectory) out += hybris.absolutePath to GUEST_LIB_DIR
        return out
    }

    fun mergeBinds(
        context: Context,
        extra: Map<String, String>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((host, guest) in directoryBinds(context)) {
            out[host] = guest
        }
        out.putAll(extra)
        return out
    }

    fun compositorEnvExtras(context: Context): Map<String, String> {
        if (!isEnabled(context)) return emptyMap()
        return mapOf(
            "POCKETLINUX_LIBHYBRIS" to "1",
            "POCKETLINUX_HYBRIS_HOST" to hostLibDir(context).absolutePath,
            "POCKETLINUX_WAYLAND_GFX" to TawcWaylandCompat.GFX_HYBRIS,
        )
    }

    fun glvndVendorJson(): String = """
        {
            "file_format_version": "1.0.0",
            "ICD": {
                "library_path": "$GUEST_LIB_DIR/libEGL.so.1"
            }
        }
    """.trimIndent() + "\n"
}
