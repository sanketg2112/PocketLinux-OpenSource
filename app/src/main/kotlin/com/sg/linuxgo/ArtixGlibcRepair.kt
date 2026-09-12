package com.sg.linuxgo

import java.io.File

/**
 * Rolling ARMtix packages (glib2, curl, python) need GLIBC_2.43+.
 * A year-old rootfs plus `pacman -S` without `-Su` leaves XFCE unable to start.
 */
internal const val ARTIX_GLIBC_REPAIR_STAMP = "var/lib/pocketlinux/artix_glibc_repair_v1"

internal fun needsArtixGlibcRepair(rootfs: File): Boolean {
    if (!ArchPacmanSecurity.looksArtixRootfs(rootfs)) return false
    return !File(rootfs, ARTIX_GLIBC_REPAIR_STAMP).isFile
}

internal fun artixGlibcRepairScript(): String {
    return """
        set +e
        export LANG=C
        if ! command -v pacman >/dev/null 2>&1; then
          echo "! pacman not found"
          exit 1
        fi
        echo "Artix: upgrading glibc/base so glib2/curl/XFCE can run…"
        pacman -Syy --noconfirm --noprogressbar --disable-download-timeout || exit 1
        pacman -S --noconfirm --needed --overwrite '*' --noprogressbar --disable-download-timeout \
          glibc filesystem gcc-libs pcre2
        ec=${'$'}?
        echo "artix glibc repair exit=${'$'}ec"
        exit ${'$'}ec
    """.trimIndent()
}

fun GuiSessionManager.repairArtixGlibcIfNeeded(rootFsDir: File) {
    if (!needsArtixGlibcRepair(rootFsDir)) return
    onLog("Artix: upgrading glibc so XFCE can start (one-time, needs network)…")
    activity.runOnUiThread {
        viewDelegate.showLoadingStatus("Artix: upgrading glibc…")
    }
    try {
        val runner = ProotRunner(activity, { line ->
            if (line.startsWith("!") ||
                line.startsWith("✓") ||
                line.startsWith("Artix:") ||
                line.contains("error", ignoreCase = true) ||
                line.contains("installing", ignoreCase = true) ||
                line.contains("upgrading", ignoreCase = true) ||
                line.contains("downloading", ignoreCase = true) ||
                line.contains("exit=")
            ) {
                onLog(line)
            }
        }, ProotBinary.Purpose.SETUP)
        val code = runner.executeCommand(rootFsDir.absolutePath, artixGlibcRepairScript())
        if (code == 0) {
            val stamp = File(rootFsDir, ARTIX_GLIBC_REPAIR_STAMP)
            stamp.parentFile?.mkdirs()
            stamp.writeText("ok\n")
            onLog("✓ Artix glibc repair done")
        } else {
            onLog("! Artix glibc repair failed (exit=$code). XFCE may stay black until glibc is current.")
        }
    } catch (e: Exception) {
        onLog("! Artix glibc repair: ${e.message}")
        android.util.Log.e("GuiSessionManager", "repairArtixGlibcIfNeeded", e)
    }
}
