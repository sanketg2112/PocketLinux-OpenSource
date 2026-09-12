package com.sg.linuxgo.restore

import java.io.File

/** True if [file] looks like our dpkg/apt hardlink-shim package wrapper. */
fun isOurPackageWrapper(file: File): Boolean {
    return try {
        file.readText().contains("preload hardlink shim")
    } catch (_: Exception) {
        false
    }
}

/** True if [file] looks like our PocketLinux sudo shim (not real setuid sudo). */
fun isOurSudoShim(file: File): Boolean {
    return try {
        val t = file.readText()
        t.contains("NO_NEW_PRIVS") || t.contains("proot -0") || t.contains("PocketLinux:")
    } catch (_: Exception) {
        false
    }
}
