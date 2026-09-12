package com.sg.linuxgo.bootstrap

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private const val TAG = "BootstrapGpuOverlay"

/** Mesa build used by install scripts (lfdevs android-container releases). */
// Keep in sync with install_desktop_x11_*.sh when bumping.
private const val MESA_VERSION = "26.2.0-devel-20260709"
private const val MESA_RELEASE = "mesa-$MESA_VERSION"
private const val TURNIP_RELEASE = "turnip-$MESA_VERSION"
private const val MESA_BASE =
    "https://github.com/lfdevs/mesa-for-android-container/releases/download/$MESA_RELEASE"
private const val TURNIP_BASE =
    "https://github.com/lfdevs/mesa-for-android-container/releases/download/$TURNIP_RELEASE"
/** Real mesa tarballs are tens of MB; smaller cache files are treated as corrupt. */
private const val MIN_MESA_ARCHIVE_BYTES = 5_000_000L

/**
 * Guest path used as the *only* LIBGL_DRIVERS_PATH for Freedreno mode.
 * Contains kgsl_dri.so (and never zink_dri.so) so stock Debian Mesa cannot
 * fall through to Zink when MESA_LOADER_DRIVER_OVERRIDE=kgsl fails.
 */
const val FREEDRENO_DRI_JAIL_GUEST = "/usr/local/lib/pocketlinux-dri"

/**
 * Mesa 25+/lfdevs: every `*_dri.so` is a small symlink to `libdril_dri.so`; the real
 * GPU code lives in `libgallium-*-devel.so` (~28MB for lfdevs). Stock Debian also uses
 * libdril stubs but its libgallium has **no** Android KGSL backend — so kgsl override
 * falls through to Zink.
 *
 * "Real Android KGSL ready" = kgsl_dri.so entry exists **and** fat lfdevs libgallium
 * (`*-devel.so` multi‑MB), or a legacy multi‑MB kgsl_dri.so binary.
 */
private const val MIN_LIBGALLIUM_BYTES = 10_000_000L
private const val MIN_LEGACY_KGSL_DRI_BYTES = 512_000L
private const val MESA_KGSL_MARKER = "var/lib/pocketlinux/mesa_android_kgsl"

/** True when rootfs can run native Freedreno via Android KGSL (lfdevs Mesa). */
fun rootfsHasKgslDri(rootfs: File): Boolean = androidKgslMesaPresent(rootfs)

/**
 * Host-side path to guest kgsl_dri.so for the dri jail (may be a libdril symlink).
 * Only returned when [androidKgslMesaPresent] is true, or when the dri file itself is fat.
 */
fun findKgslDriHostFile(rootfs: File): File? {
    val dirs = listOf(
        File(rootfs, "usr/lib/aarch64-linux-gnu/dri"),
        File(rootfs, "usr/lib/dri"),
        File(rootfs, "usr/lib/xorg/modules/dri")
    )
    val androidReady = androidKgslMesaPresent(rootfs)
    for (d in dirs) {
        val f = File(d, "kgsl_dri.so")
        if (!f.exists()) continue
        if (androidReady) return f
        if (isLegacyFatKgslDri(f)) return f
    }
    return null
}

/** lfdevs Android-container Mesa is present (KGSL usable). */
fun androidKgslMesaPresent(rootfs: File): Boolean {
    if (!kgslDriEntryExists(rootfs)) return false
    if (findLfdevsLibgallium(rootfs) != null) return true
    // Legacy fat kgsl_dri.so (pre-libdril packaging)
    if (findLegacyFatKgslDri(rootfs) != null) return true
    // Install marker from a prior successful PocketLinux overlay extract
    val marker = File(rootfs, MESA_KGSL_MARKER)
    if (marker.isFile && findAnyLibgallium(rootfs)?.let { it.length() >= MIN_LIBGALLIUM_BYTES } == true) {
        return true
    }
    return false
}

/**
 * Whether [kgslFile] is usable for Android KGSL.
 * Accepts modern libdril stub **only if** rootfs has fat lfdevs libgallium-*-devel.so
 * (pass [rootfs] via driDir's parent's parent… better use overload).
 */
fun isRealAndroidKgslDri(kgslFile: File, driDir: File? = null): Boolean {
    if (!kgslFile.exists()) return false
    if (isLegacyFatKgslDri(kgslFile)) return true
    // Walk up to rootfs: …/usr/lib/aarch64-linux-gnu/dri → rootfs
    var p = driDir ?: kgslFile.parentFile
    repeat(6) {
        if (p == null) return@repeat
        if (findLfdevsLibgallium(p) != null) return true
        // If this looks like a rootfs (has usr/), check there
        if (File(p, "usr").isDirectory && findLfdevsLibgallium(p) != null) return true
        p = p.parentFile
    }
    return false
}

private fun kgslDriEntryExists(rootfs: File): Boolean {
    val paths = listOf(
        "usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so",
        "usr/lib/dri/kgsl_dri.so",
        "usr/lib/xorg/modules/dri/kgsl_dri.so"
    )
    return paths.any { File(rootfs, it).exists() }
}

/** lfdevs ships `libgallium-26.2.0-devel.so` (~28MB). Stock Debian uses non-devel names. */
fun findLfdevsLibgallium(rootfs: File): File? {
    val dirs = listOf(
        File(rootfs, "usr/lib/aarch64-linux-gnu"),
        File(rootfs, "usr/lib")
    )
    for (d in dirs) {
        if (!d.isDirectory) continue
        val hit = d.listFiles()?.firstOrNull { f ->
            f.isFile &&
                f.name.startsWith("libgallium") &&
                f.name.endsWith(".so") &&
                f.name.contains("devel") &&
                f.length() >= MIN_LIBGALLIUM_BYTES
        }
        if (hit != null) return hit
    }
    return null
}

private fun findAnyLibgallium(rootfs: File): File? {
    val dirs = listOf(
        File(rootfs, "usr/lib/aarch64-linux-gnu"),
        File(rootfs, "usr/lib")
    )
    for (d in dirs) {
        if (!d.isDirectory) continue
        val hit = d.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.startsWith("libgallium") && f.name.endsWith(".so")
        }
        if (hit != null) return hit
    }
    return null
}

private fun isLegacyFatKgslDri(kgslFile: File): Boolean {
    val resolved = try {
        kgslFile.canonicalFile
    } catch (_: Exception) {
        kgslFile
    }
    val name = resolved.name.lowercase()
    if (name.contains("libdril")) return false
    return try {
        resolved.length() >= MIN_LEGACY_KGSL_DRI_BYTES
    } catch (_: Exception) {
        false
    }
}

private fun findLegacyFatKgslDri(rootfs: File): File? {
    val dirs = listOf(
        File(rootfs, "usr/lib/aarch64-linux-gnu/dri"),
        File(rootfs, "usr/lib/dri"),
        File(rootfs, "usr/lib/xorg/modules/dri")
    )
    for (d in dirs) {
        val f = File(d, "kgsl_dri.so")
        if (f.exists() && isLegacyFatKgslDri(f)) return f
    }
    return null
}

fun writeMesaKgslMarker(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        val f = File(rootfs, MESA_KGSL_MARKER)
        f.parentFile?.mkdirs()
        f.writeText("$MESA_VERSION\n")
        onLog("GPU: wrote mesa_android_kgsl marker ($MESA_VERSION)")
    } catch (e: Exception) {
        Log.w(TAG, "writeMesaKgslMarker: ${e.message}")
    }
}

/**
 * Build /usr/local/lib/pocketlinux-dri with a symlink to kgsl only (no zink).
 * Returns true if the jail has a usable kgsl_dri.so for the guest.
 */
fun prepareFreedrenoDriJail(rootfs: File, onLog: (String) -> Unit = {}): Boolean {
    val jail = File(rootfs, "usr/local/lib/pocketlinux-dri")
    try {
        if (!jail.isDirectory) jail.mkdirs()
        // Drop any previous dri links (including accidental zink).
        jail.listFiles()?.forEach { f ->
            if (f.name.endsWith("_dri.so") || f.name.endsWith(".so")) {
                f.delete()
            }
        }
        val kgsl = findKgslDriHostFile(rootfs) ?: run {
            onLog("GPU: no kgsl_dri.so to jail — Freedreno native unavailable")
            return false
        }
        // Symlink using guest-absolute target so the loader resolves inside proot.
        val guestTarget = when {
            kgsl.absolutePath.contains("aarch64-linux-gnu") ->
                "/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
            kgsl.absolutePath.contains("xorg/modules") ->
                "/usr/lib/xorg/modules/dri/kgsl_dri.so"
            else -> "/usr/lib/dri/kgsl_dri.so"
        }
        val link = File(jail, "kgsl_dri.so")
        link.delete()
        val ok = try {
            ProcessBuilder("ln", "-sfn", guestTarget, link.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
            link.exists() || FilesIsSymlink(link)
        } catch (_: Exception) {
            // Fallback: copy (larger but works if ln blocked)
            kgsl.copyTo(link, overwrite = true)
            link.isFile
        }
        // Soft-GL fallback modules so a stale jail-only LIBGL_DRIVERS_PATH still finds swrast
        // (Alpine: MESA-LOADER failed to open swrast under pocketlinux-dri).
        for (mod in listOf("swrast", "kms_swrast")) {
            val src = listOf(
                File(rootfs, "usr/lib/dri/${mod}_dri.so"),
                File(rootfs, "usr/lib/aarch64-linux-gnu/dri/${mod}_dri.so"),
                File(rootfs, "usr/lib/xorg/modules/dri/${mod}_dri.so")
            ).firstOrNull { it.exists() } ?: continue
            val guestSrc = when {
                src.absolutePath.contains("aarch64-linux-gnu") ->
                    "/usr/lib/aarch64-linux-gnu/dri/${mod}_dri.so"
                src.absolutePath.contains("xorg/modules") ->
                    "/usr/lib/xorg/modules/dri/${mod}_dri.so"
                else -> "/usr/lib/dri/${mod}_dri.so"
            }
            val dst = File(jail, "${mod}_dri.so")
            try {
                ProcessBuilder("ln", "-sfn", guestSrc, dst.absolutePath)
                    .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
        if (ok) {
            onLog("GPU: dri jail ready → $FREEDRENO_DRI_JAIL_GUEST (kgsl + swrast fallback)")
        }
        return ok
    } catch (e: Exception) {
        Log.w(TAG, "prepareFreedrenoDriJail: ${e.message}")
        onLog("! GPU: dri jail failed: ${e.message}")
        return false
    }
}

private fun FilesIsSymlink(f: File): Boolean =
    try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Exception) {
        false
    }

/**
 * When the user picks Freedreno/Zink, golden Debian images often only have stock
 * Mesa (msm/zink, no kgsl). Without lfdevs kgsl_dri.so, MESA_LOADER=kgsl fails
 * and Mesa falls through to Zink+Turnip (glmark2 "zink Vulkan … MESA_TURNIP").
 *
 * Best-effort; returns true if kgsl is present after (or already was).
 */
fun ensureHardwareGpuMesaOverlay(
    rootfs: File,
    driverMode: String,
    onLog: (String) -> Unit = {}
): Boolean {
    val mode = driverMode.trim().let { resolveGpuDriverMode(it, rootfs) }
    // Only Adreno hardware paths need kgsl
    if (mode != "adreno_freedreno" && mode != "adreno_zink") {
        return true
    }

    if (rootfsHasKgslDri(rootfs)) {
        val g = findLfdevsLibgallium(rootfs)
        onLog(
            "GPU: Android KGSL Mesa ready" +
                (g?.let { " (${it.name}, ${it.length() / 1_000_000}MB)" } ?: "")
        )
        if (mode == "adreno_freedreno") {
            prepareFreedrenoDriJail(rootfs, onLog)
        }
        return true
    }

    val family = detectGpuOverlayFamily(rootfs)
    onLog("GPU: lfdevs Android Mesa not detected — installing overlay ($family)…")
    Log.i(TAG, "ensureHardwareGpuMesaOverlay family=$family rootfs=${rootfs.absolutePath}")

    fun finishOk(label: String): Boolean {
        writeMesaKgslMarker(rootfs, onLog)
        val g = findLfdevsLibgallium(rootfs)
        onLog(
            "GPU: $label" +
                (g?.let { " — ${it.name} ${it.length() / 1_000_000}MB" } ?: "")
        )
        if (mode == "adreno_freedreno") prepareFreedrenoDriJail(rootfs, onLog)
        return true
    }

    return try {
        installDebianStyleMesaForFamily(rootfs, onLog, family, forceRedownload = false)
        if (rootfsHasKgslDri(rootfs)) return finishOk("Android KGSL Mesa ready after overlay")
        onLog("GPU: still not detected after extract — clearing cache and retrying…")
        logGalliumProbe(rootfs, onLog)
        installDebianStyleMesaForFamily(rootfs, onLog, family, forceRedownload = true)
        if (rootfsHasKgslDri(rootfs)) return finishOk("Android KGSL Mesa ready after retry")
        logGalliumProbe(rootfs, onLog)
        onLog("! GPU: overlay finished but Android KGSL Mesa still not detected — software GL")
        false
    } catch (e: Exception) {
        Log.e(TAG, "ensureHardwareGpuMesaOverlay failed", e)
        onLog("! GPU: Mesa overlay failed: ${e.message}")
        false
    }
}

private fun logGalliumProbe(rootfs: File, onLog: (String) -> Unit) {
    val dri = File(rootfs, "usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so")
    val target = try {
        if (dri.exists()) dri.canonicalFile.name else "missing"
    } catch (_: Exception) {
        "err"
    }
    val g = findAnyLibgallium(rootfs)
    onLog(
        "GPU: probe kgsl_dri→$target libgallium=" +
            (g?.let { "${it.name}(${it.length()})" } ?: "none") +
            " lfdevs=" + (findLfdevsLibgallium(rootfs)?.name ?: "none")
    )
}

private fun installDebianStyleMesaForFamily(
    rootfs: File,
    onLog: (String) -> Unit,
    family: String,
    forceRedownload: Boolean
) {
    when (family) {
        "ubuntu" -> installDebianStyleMesa(
            rootfs, onLog, pkgFamily = "ubuntu_noble", forceRedownload = forceRedownload
        )
        "alpine" -> installDebianStyleMesa(
            rootfs, onLog, pkgFamily = "debian_trixie", alpineCompat = true,
            forceRedownload = forceRedownload
        )
        else -> installDebianStyleMesa(
            rootfs, onLog, pkgFamily = "debian_trixie", forceRedownload = forceRedownload
        )
    }
}

private fun detectGpuOverlayFamily(rootfs: File): String {
    if (File(rootfs, "etc/arch-release").isFile) return "arch"
    val os = try {
        File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText().orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }
    return when {
        os.contains("id=alpine") || os.contains("id=\"alpine\"") -> "alpine"
        os.contains("ubuntu") || os.contains("noble") || os.contains("jammy") -> "ubuntu"
        os.contains("kali") -> "debian"
        os.contains("id=artix") || os.contains("id=\"artix\"") -> "arch"
        else -> "debian"
    }
}

private fun installDebianStyleMesa(
    rootfs: File,
    onLog: (String) -> Unit,
    pkgFamily: String,
    alpineCompat: Boolean = false,
    forceRedownload: Boolean = false
) {
    val mesaPkg = "mesa-for-android-container_${MESA_VERSION}_${pkgFamily}_arm64.tar.gz"
    val turnipPkg = "turnip_${MESA_VERSION}_${pkgFamily}_arm64.tar.gz"
    val cache = File(rootfs, "var/cache/pocketlinux").apply { mkdirs() }
    val mesaFile = File(cache, mesaPkg)
    val turnipFile = File(cache, turnipPkg)
    if (forceRedownload) {
        mesaFile.delete()
        turnipFile.delete()
        File(mesaFile.absolutePath + ".part").delete()
        File(turnipFile.absolutePath + ".part").delete()
    }
    downloadUrl("$MESA_BASE/$mesaPkg", mesaFile, onLog, minBytes = MIN_MESA_ARCHIVE_BYTES)
    downloadUrl("$TURNIP_BASE/$turnipPkg", turnipFile, onLog, minBytes = 500_000L)
    extractTarGz(mesaFile, rootfs, onLog)
    extractTarGz(turnipFile, rootfs, onLog)
    if (alpineCompat) {
        val ld = File(rootfs, "lib/ld-linux-aarch64.so.1")
        val gcompat = File(rootfs, "lib/libgcompat.so.0")
        if (!ld.exists() && gcompat.isFile) {
            try {
                ld.parentFile?.mkdirs()
                ProcessBuilder("ln", "-sf", "libgcompat.so.0", ld.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }
}

private fun downloadUrl(
    url: String,
    dest: File,
    onLog: (String) -> Unit,
    minBytes: Long = MIN_MESA_ARCHIVE_BYTES
) {
    if (dest.isFile && dest.length() >= minBytes) {
        onLog("GPU: reusing ${dest.name} (${dest.length() / 1_000_000}MB)")
        return
    }
    if (dest.isFile) {
        onLog("GPU: discarding short/corrupt ${dest.name} (${dest.length()} bytes)")
        dest.delete()
    }
    onLog("GPU: downloading ${dest.name}…")
    dest.parentFile?.mkdirs()
    val tmp = File(dest.absolutePath + ".part")
    if (tmp.exists()) tmp.delete()
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 45_000
        readTimeout = 300_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "PocketLinux-Android")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw Exception("HTTP $code for ${dest.name}")
        }
        val contentLen = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val got = tmp.length()
        if (got < minBytes) {
            tmp.delete()
            val cl = if (contentLen > 0) ", Content-Length=$contentLen" else ""
            throw Exception("${dest.name} too small ($got bytes$cl) — download incomplete")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        onLog("GPU: downloaded ${dest.name} (${dest.length() / 1_000_000}MB)")
    } finally {
        conn.disconnect()
        if (tmp.exists()) tmp.delete()
    }
}

private fun extractTarGz(archive: File, rootfs: File, onLog: (String) -> Unit) {
    if (!archive.isFile || archive.length() < 1000L) {
        throw Exception("missing archive ${archive.name}")
    }
    onLog("GPU: extracting ${archive.name}…")
    val pb = ProcessBuilder(
        "tar", "-xzf", archive.absolutePath, "-C", rootfs.absolutePath
    )
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val ok = p.waitFor(180, TimeUnit.SECONDS)
    if (!ok) {
        p.destroyForcibly()
        throw Exception("tar extract timeout ${archive.name}")
    }
    if (p.exitValue() != 0) {
        throw Exception("tar failed (${p.exitValue()}): ${out.take(200)}")
    }
}
