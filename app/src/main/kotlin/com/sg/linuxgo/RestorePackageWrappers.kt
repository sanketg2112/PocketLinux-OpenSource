package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** File manager / dpkg / sudo wrappers. */

internal fun ContainerRestoreEngine.installFileManagerUidWrappers(rootfs: File) {
    // Arch ships /usr/bin/Thunar (capital T); Debian uses thunar. Cover both + sbin copies.
    val apps = listOf(
        "pcmanfm-qt" to "/usr/bin/pcmanfm-qt",
        "pcmanfm" to "/usr/bin/pcmanfm",
        "thunar" to "/usr/bin/thunar",
        "Thunar" to "/usr/bin/Thunar"
    )
    // Broken legacy wrappers (e.g. /usr/sbin/Thunar → missing Thunar.real) cause log spam
    // and failed desktop components on Arch. Remove if they only point at missing .real.
    for (orphan in listOf(
        "usr/sbin/Thunar", "usr/sbin/thunar", "usr/local/bin/Thunar", "usr/local/bin/thunar"
    )) {
        val f = File(rootfs, orphan)
        if (!f.isFile) continue
        try {
            val text = f.readText()
            if (text.contains(".real") && text.length < 8000) {
                val pointsTo = listOf(
                    File(rootfs, "usr/bin/Thunar.real"),
                    File(rootfs, "usr/bin/thunar.real"),
                    File(rootfs, "usr/bin/Thunar"),
                    File(rootfs, "usr/bin/thunar")
                )
                val anyReal = pointsTo.any { it.isFile && it.length() > 5000L }
                if (!anyReal) f.delete()
            }
        } catch (_: Exception) {
        }
    }
    for ((name, real) in apps) {
        val fileManagerBin = File(rootfs, real.removePrefix("/"))
        if (!fileManagerBin.isFile) continue

        // Clean up legacy wrappers in /usr/local/bin
        val oldWrap = File(rootfs, "usr/local/bin/$name")
        if (oldWrap.exists()) {
            oldWrap.delete()
        }

        // Check if it's already our wrapper
        var isWrapper = false
        try {
            if (fileManagerBin.length() < 5000L) {
                if (fileManagerBin.readText().contains("PocketLinux file-manager wrapper")) {
                    isWrapper = true
                }
            }
        } catch (_: Exception) {}

        val realTarget = File(rootfs, (real + ".real").removePrefix("/"))
        // Also accept alternate case .real left by older installs
        val altReal = when (name) {
            "thunar" -> File(rootfs, "usr/bin/Thunar.real")
            "Thunar" -> File(rootfs, "usr/bin/thunar.real")
            else -> null
        }
        if (!isWrapper) {
            // Don't wrap a symlink that already points at the other casing we wrap
            if (fileManagerBin.length() < 5000L) {
                try {
                    val t = fileManagerBin.readText()
                    if (t.startsWith("#!") && t.contains(".real")) {
                        isWrapper = true
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (!isWrapper) {
            if (realTarget.exists()) {
                realTarget.delete()
            }
            fileManagerBin.renameTo(realTarget)
        }

        // Resolve which binary to exec (handle capital-T Arch packaging)
        val execCandidates = listOf(
            real + ".real",
            if (name == "thunar") "/usr/bin/Thunar.real" else if (name == "Thunar") "/usr/bin/thunar.real" else null,
            if (name.equals("thunar", ignoreCase = true)) "/usr/bin/Thunar" else null,
            if (name.equals("thunar", ignoreCase = true)) "/usr/bin/thunar" else null,
            real
        ).filterNotNull().distinct()
        val execLine = execCandidates.joinToString("\n") { cand ->
            """if [ -x $cand ] && ! head -1 $cand 2>/dev/null | grep -q '^#!'; then
  exec $cand "${'$'}@"
fi"""
        }

        // Always write the wrapper to /usr/bin/fm
        val script = """
            #!/bin/sh
            # PocketLinux file-manager wrapper
            # 1) Hide red "Root Instance" banner under proot -0 (optional spoof)
            # 2) Disable gvfs/udisks volume monitors that hang Thunar under PRoot
            SPOOF=/usr/lib/pocketlinux_uid_spoof.so
            if [ -f "${'$'}SPOOF" ]; then
              export LD_PRELOAD="${'$'}SPOOF${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
            fi
            # Avoid gvfs/udisks volume-monitor D-Bus deadlocks under PRoot
            export GIO_USE_VOLUME_MONITOR=unix
            export GVFS_DISABLE_FUSE=1
            export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1
            export GIO_USE_VFS=local
            # Disable GL and force software rendering to prevent black/blank screens in GTK file managers (e.g., Thunar)
            export GDK_GL=disable
            export GDK_DEBUG=nogl
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=swrast
            # Kill stuck thumbnailers that freeze Thunar on folder open
            pkill -x tumblerd 2>/dev/null || true
            $execLine
            echo "file manager binary missing for $name" >&2
            exit 1
        """.trimIndent() + "\n"
        fileManagerBin.writeText(script)
        fileManagerBin.setExecutable(true, false)
        // Keep altReal only if needed; avoid leaving broken dual wrappers
        altReal?.let { /* keep if present */ }
    }

    // Delete D-Bus service files for volume monitors, tumbler, and system services (color manager, upower, accounts, etc.)
    // to prevent them from autostarting and hanging under PRoot.
    // Using delete instead of Exec=/bin/true is critical; otherwise, clients (like xfdesktop-settings) will
    // try to activate the service and block/hang waiting on D-Bus activation timeouts.
    // xfdesktop-settings uses org.freedesktop.thumbnails.Thumbnailer1 (not only
    // org.xfce.Tumbler.*). Service file may be named either; delete both patterns.
    val dbusServicesToNeutralize = listOf(
        "org.gtk.vfs.UDisks2VolumeMonitor.service",
        "org.gtk.vfs.GoaVolumeMonitor.service",
        "org.gtk.vfs.AfcVolumeMonitor.service",
        "org.gtk.vfs.MtpVolumeMonitor.service",
        "org.gtk.vfs.GPhoto2VolumeMonitor.service",
        "org.xfce.Tumbler.Thumbnailer1.service",
        "org.xfce.Tumbler.Cache1.service",
        "org.xfce.Tumbler.Manager1.service",
        "org.freedesktop.thumbnails.Thumbnailer1.service",
        "org.freedesktop.thumbnails.Cache1.service",
        "org.freedesktop.thumbnails.Manager1.service",
        "org.freedesktop.ColorManager.service",
        "org.freedesktop.UPower.service",
        "org.freedesktop.Accounts.service",
        "org.freedesktop.RealtimeKit1.service",
        "org.freedesktop.Avahi.service",
        "org.freedesktop.ModemManager1.service",
        "org.freedesktop.GeoClue2.service",
        "org.freedesktop.bolt.service"
    )
    val servicesDirs = listOf(
        File(rootfs, "usr/share/dbus-1/services"),
        File(rootfs, "usr/share/dbus-1/system-services")
    )
    servicesDirs.forEach { dir ->
        if (dir.isDirectory) {
            dbusServicesToNeutralize.forEach { serviceName ->
                val sfile = File(dir, serviceName)
                if (sfile.exists()) {
                    try {
                        sfile.delete()
                    } catch (_: Exception) {}
                }
            }
            // Wildcard cleanup for any remaining Tumbler / thumbnailer names
            dir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("org.xfce.Tumbler") || n.startsWith("org.freedesktop.thumbnails.")) {
                    try {
                        f.delete()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Wrap xfdesktop-settings (Desktop Settings dialog freezes ~10–15s on Arch when
    // GtkFileChooserButton + tumbler activation run without GIO mitigations).
    installXfdesktopSettingsWrapper(rootfs)
}

internal fun ContainerRestoreEngine.installXfdesktopSettingsWrapper(rootfs: File) {
    val bin = File(rootfs, "usr/bin/xfdesktop-settings")
    if (!bin.isFile) return
    try {
        val isWrapper = try {
            bin.length() < 5000L && bin.readText().contains("PocketLinux xfdesktop-settings wrapper")
        } catch (_: Exception) {
            false
        }
        val real = File(rootfs, "usr/bin/xfdesktop-settings.real")
        if (!isWrapper) {
            if (real.exists()) real.delete()
            bin.renameTo(real)
        }
        if (!real.isFile) return
        bin.writeText(
            """
            #!/bin/sh
            # PocketLinux xfdesktop-settings wrapper
            export GIO_USE_VOLUME_MONITOR=unix
            export GVFS_DISABLE_FUSE=1
            export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1
            export GIO_USE_VFS=local
            export GDK_GL=disable
            pkill -x tumblerd 2>/dev/null || true
            exec /usr/bin/xfdesktop-settings.real "${'$'}@"
            """.trimIndent() + "\n"
        )
        bin.setExecutable(true, false)
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "installXfdesktopSettingsWrapper: ${e.message}")
    }
}

/**
 * Wrap dpkg/apt so only those processes preload link_shim (hardlink → copy).
 * PATH prefers /usr/local/bin so these take effect without replacing system paths permanently
 * when packages upgrade /usr/bin/dpkg.
 */
internal fun ContainerRestoreEngine.installDpkgAptWrappers(rootfs: File, localBin: File) {
    fun wrap(name: String, realCandidates: List<String>) {
        val real = realCandidates.map { File(rootfs, it.removePrefix("/")) }
            .firstOrNull { it.exists() && it.isFile }
        // Prefer .real sibling if we already wrapped before
        val realPath = when {
            File(rootfs, "usr/bin/$name.real").exists() -> "/usr/bin/$name.real"
            real != null && !isOurPackageWrapper(real) -> {
                // If /usr/bin/name is still the real binary, leave it; wrapper in local/bin wins via PATH
                "/usr/bin/$name"
            }
            else -> "/usr/bin/$name"
        }
        // If local wrapper would call itself, use .real if present
        val execTarget = if (File(rootfs, "usr/bin/$name.real").exists()) {
            "/usr/bin/$name.real"
        } else {
            realPath
        }
        val script = """
            #!/bin/sh
            # PocketLinux: preload hardlink shim only for this package tool
            if [ -f /usr/lib/link_shim.so ]; then
              export LD_PRELOAD="/usr/lib/link_shim.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
            fi
            export DEBIAN_FRONTEND="${'$'}{DEBIAN_FRONTEND:-noninteractive}"
            exec $execTarget "${'$'}@"
        """.trimIndent() + "\n"
        val out = File(localBin, name)
        out.writeText(script)
        out.setExecutable(true, false)
    }
    wrap("dpkg", listOf("/usr/bin/dpkg", "/bin/dpkg"))
    wrap("dpkg-deb", listOf("/usr/bin/dpkg-deb"))
    wrap("apt", listOf("/usr/bin/apt"))
    wrap("apt-get", listOf("/usr/bin/apt-get"))
    wrap("aptitude", listOf("/usr/bin/aptitude"))
    installXbpsWrappers(rootfs, localBin)
}

/**
 * Compact helper written by the xbps wrapper when the on-disk copy is stale.
 * Void's python3.14 provides compression.zstd; the zstd CLI is often missing.
 */
internal fun xbpsMaterializePyCompact(): String = """
    #!/usr/bin/env python3
    # PocketLinux xbps materialize v3
    from compression.zstd import ZstdFile
    import glob, os, shutil, tarfile, sys
    SKIP = {"./props.plist", "./files.plist", "./INSTALL", "./REMOVE",
            "props.plist", "files.plist", "INSTALL", "REMOVE"}
    def gp(n):
        n = n[1:] if n.startswith("./") else n
        return n if n.startswith("/") else "/" + n
    def putcopy(src, dest):
        if os.path.lexists(dest):
            os.unlink(dest)
        shutil.copyfile(src, dest)
    noextract = []
    for pkg in sorted(glob.glob("/var/cache/xbps/*.xbps")):
        pending = []
        try:
            zf = ZstdFile(pkg, "rb")
            tf = tarfile.open(fileobj=zf, mode="r|")
            try:
                for m in tf:
                    if not m.name or m.name in SKIP or m.isdir():
                        continue
                    dest = gp(m.name)
                    if m.issym() or m.islnk():
                        pending.append((dest, m.linkname or ""))
                        noextract.append(dest)
                        continue
                    if not m.isfile():
                        continue
                    d = os.path.dirname(dest)
                    if d:
                        os.makedirs(d, exist_ok=True)
                    src = tf.extractfile(m)
                    if src is None:
                        continue
                    if os.path.lexists(dest):
                        os.unlink(dest)
                    with open(dest, "wb") as out:
                        shutil.copyfileobj(src, out)
            except tarfile.ReadError:
                pass
            try:
                tf.close()
            except Exception:
                pass
            try:
                zf.close()
            except Exception:
                pass
            for dest, link in pending:
                d = os.path.dirname(dest)
                if d:
                    os.makedirs(d, exist_ok=True)
                tgt = link if link.startswith("/") else os.path.join(d or "/", link)
                if os.path.isfile(tgt):
                    try:
                        putcopy(tgt, dest)
                    except OSError:
                        pass
        except Exception as exc:
            sys.stderr.write("materialize %s: %s\n" % (pkg, exc))
    os.makedirs("/etc/xbps.d", exist_ok=True)
    open("/etc/xbps.d/00-pocketlinux-noextract.conf", "w").write(
        "".join("noextract=%s\n" % p for p in noextract)
    )
""".trimIndent() + "\n"

/**
 * xbps unpacks soname links via symlink() (e.g. libatomic.so.1 → libatomic.so.1.2.0).
 * PRoot turns that into ENOENT. Preload shims, then on unpack failure copy those
 * links as regular files, tell xbps noextract, and retry.
 */
internal fun xbpsLinkShimWrapperScript(execTarget: String): String {
    val py = xbpsMaterializePyCompact().trimEnd()
    val head = """
        #!/bin/sh
        # PocketLinux xbps unpack shim v6
        cd / || true
        export PWD=/
        export XBPS_ARCH="${'$'}{XBPS_ARCH:-aarch64}"
        mkdir -p /usr/lib /usr/local/bin /var/cache/xbps /var/db/xbps /etc/xbps.d /tmp
        _pl=""
        if [ -f /usr/lib/xbps_extract_shim.so ]; then
          _pl=/usr/lib/xbps_extract_shim.so
        fi
        if [ -f /usr/lib/link_shim.so ]; then
          _pl="${'$'}{_pl:+${'$'}_pl:}/usr/lib/link_shim.so"
        fi
        _real="$execTarget"
        _run() {
          if [ -n "${'$'}_pl" ]; then
            LD_PRELOAD="${'$'}_pl${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}" "${'$'}_real" "${'$'}@"
          else
            "${'$'}_real" "${'$'}@"
          fi
        }
        _log=/tmp/pocketlinux-xbps.log
        _rcf=/tmp/pocketlinux-xbps.rc
        rm -f "${'$'}_rcf"
        (_run "${'$'}@" ; echo ${'$'}? > "${'$'}_rcf") 2>&1 | tee "${'$'}_log"
        _rc=${'$'}(cat "${'$'}_rcf" 2>/dev/null || echo 1)
        if [ "${'$'}_rc" = "0" ]; then exit 0; fi
        if grep -q "failed to extract file" "${'$'}_log" 2>/dev/null; then
          echo "PocketLinux: copying library links PRoot cannot create, retrying unpack..." >&2
          _mat=/usr/local/bin/pocketlinux-xbps-materialize
          if ! grep -q "materialize v3" "${'$'}_mat" 2>/dev/null; then
            cat > "${'$'}_mat" << 'PLMATV2'
    """.trimIndent()
    val tail = """
        PLMATV2
            chmod 755 "${'$'}_mat" 2>/dev/null || true
          fi
          python3 "${'$'}_mat" || true
          mkdir -p /etc/xbps.d
          grep -o 'failed to extract file `[^`]*' "${'$'}_log" 2>/dev/null | sed 's/.*`//' | while read -r _rel; do
            [ -n "${'$'}_rel" ] || continue
            _dest=${'$'}{_rel#./}
            case "${'$'}_dest" in
              /*) ;;
              *) _dest="/${'$'}_dest" ;;
            esac
            if [ ! -f "${'$'}_dest" ]; then
              for _f in "${'$'}_dest".*; do
                if [ -f "${'$'}_f" ] && [ ! -L "${'$'}_f" ]; then
                  cp -f "${'$'}_f" "${'$'}_dest" 2>/dev/null || true
                  break
                fi
              done
            fi
            if [ -f "${'$'}_dest" ]; then
              echo "noextract=${'$'}_dest" >> /etc/xbps.d/00-pocketlinux-noextract.conf
            fi
          done
          _has_y=0
          for _a in "${'$'}@"; do
            case "${'$'}_a" in -y|--yes) _has_y=1 ;; esac
          done
          rm -f "${'$'}_rcf"
          if [ "${'$'}_has_y" = 1 ]; then
            (_run "${'$'}@" ; echo ${'$'}? > "${'$'}_rcf") 2>&1 | tee -a "${'$'}_log"
          else
            (_run -y "${'$'}@" ; echo ${'$'}? > "${'$'}_rcf") 2>&1 | tee -a "${'$'}_log"
          fi
          _rc=${'$'}(cat "${'$'}_rcf" 2>/dev/null || echo 1)
          rm -f /etc/xbps.d/00-pocketlinux-noextract.conf
        fi
        exit "${'$'}_rc"
    """.trimIndent()
    return head + "\n" + py + "\n" + tail + "\n"
}

internal fun ContainerRestoreEngine.installXbpsWrappers(rootfs: File, localBin: File) {
    installXbpsWrappersOnto(rootfs, localBin)
}

internal fun installXbpsWrappersOnto(rootfs: File, localBin: File) {
    if (!File(rootfs, "usr/bin/xbps-install").isFile &&
        !File(rootfs, "usr/bin/xbps-install.real").isFile
    ) {
        return
    }
    for (name in listOf("xbps-install", "xbps-remove", "xbps-reconfigure")) {
        val bin = File(rootfs, "usr/bin/$name")
        val real = File(rootfs, "usr/bin/$name.real")
        if (!bin.isFile && !real.isFile) continue
        val already = try {
            bin.isFile && bin.length() < 64000L && bin.readText().contains("PocketLinux xbps")
        } catch (_: Exception) {
            false
        }
        if (bin.isFile && !already) {
            if (real.exists()) real.delete()
            bin.renameTo(real)
        }
        if (!real.isFile) continue
        val script = xbpsLinkShimWrapperScript("/usr/bin/$name.real")
        bin.writeText(script)
        bin.setExecutable(true, false)
        val local = File(localBin, name)
        local.writeText(script)
        local.setExecutable(true, false)
    }
    val mat = File(rootfs, "usr/local/bin/pocketlinux-xbps-materialize")
    val matText = try {
        if (mat.isFile) mat.readText() else ""
    } catch (_: Exception) {
        ""
    }
    if (!matText.contains("materialize v3")) {
        mat.parentFile?.mkdirs()
        mat.writeText(xbpsMaterializePyCompact())
        mat.setExecutable(true, false)
        mat.setReadable(true, false)
    }
}

internal fun ContainerRestoreEngine.isOurPackageWrapper(file: File): Boolean =
    com.sg.linuxgo.restore.isOurPackageWrapper(file)

/**
 * Clear stale dpkg locks/backups and ensure writable /var/lib/dpkg.
 * Also fixes /var/run layout so base-files postinst can configure
 * (it runs `rmdir /var/run` when migrating to a symlink → /run).
 */
fun ContainerRestoreEngine.repairDpkgState(rootfs: File, onLog: (String) -> Unit = {}) {
    val dpkg = File(rootfs, "var/lib/dpkg").apply { mkdirs() }
    listOf("info", "updates", "parts", "triggers", "alternatives").forEach {
        File(dpkg, it).mkdirs()
    }
    // Stale locks / partial backups from interrupted apt
    listOf(
        "lock", "lock-frontend", "status-old", "status-new",
        "updates/tmp.i", "updates/tmp.i.new"
    ).forEach { rel ->
        File(dpkg, rel).delete()
    }
    File(rootfs, "var/lib/dpkg/lock").delete()
    File(rootfs, "var/cache/apt/archives/lock").delete()
    File(rootfs, "var/lib/apt/lists/lock").delete()

    val status = File(dpkg, "status")
    if (!status.exists()) {
        status.writeText("")
    }
    // Make tree writable for proot root session
    dpkg.walkTopDown().forEach { f ->
        f.setReadable(true, false)
        f.setWritable(true, false)
        if (f.isDirectory) f.setExecutable(true, false)
    }

    ensureVarRunSymlink(rootfs)
    softPatchBaseFilesPostinst(rootfs)

    onLog("✓ dpkg state cleaned (locks/status-old, /var/run)")
}

/**
 * Debian base-files postinst does roughly:
 *   if /var/run is a real dir: rmdir /var/run; ln -s /run /var/run
 * Under PRoot we often have a non-empty /var/run (dbus, etc.) so rmdir
 * fails and base-files stays half-configured, blocking apt forever.
 *
 * Merge contents into /run and replace /var/run with a symlink.
 */
fun ContainerRestoreEngine.ensureVarRunSymlink(rootfs: File) {
    val runDir = File(rootfs, "run").apply { mkdirs() }
    val varRun = File(rootfs, "var/run")
    File(rootfs, "var").mkdirs()

    try {
        val varRunIsLink = try {
            java.nio.file.Files.isSymbolicLink(varRun.toPath())
        } catch (_: Exception) {
            false
        }

        if (varRunIsLink) {
            // Already a symlink — ensure /run exists
            runDir.mkdirs()
        } else if (varRun.exists() && varRun.isDirectory) {
            // Merge children into /run then replace with symlink
            varRun.listFiles()?.forEach { child ->
                val dest = File(runDir, child.name)
                if (!dest.exists()) {
                    try {
                        child.copyRecursively(dest, overwrite = false)
                    } catch (_: Exception) { }
                }
            }
            varRun.deleteRecursively()
        } else if (varRun.exists()) {
            varRun.delete()
        }

        if (!varRun.exists()) {
            // Relative symlink var/run -> ../run (works inside the rootfs tree)
            java.nio.file.Files.createSymbolicLink(
                varRun.toPath(),
                java.nio.file.Paths.get("../run")
            )
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureVarRunSymlink: ${e.message}")
    }

    // Common runtime dirs base-files / dbus expect (under /run, visible via /var/run)
    listOf("run/dbus", "run/user", "run/lock", "run/shm").forEach { rel ->
        File(rootfs, rel).mkdirs()
    }
    runDir.setWritable(true, false)
    runDir.setExecutable(true, false)
}

/**
 * Soften base-files postinst rmdir so a leftover non-empty /var/run cannot
 * wedge dpkg --configure forever under PRoot.
 */
internal fun ContainerRestoreEngine.softPatchBaseFilesPostinst(rootfs: File) {
    val postinst = File(rootfs, "var/lib/dpkg/info/base-files.postinst")
    if (!postinst.exists()) return
    try {
        var text = postinst.readText()
        if (text.contains("POCKETLINUX_BASE_FILES_PATCH")) return
        // Make bare `rmdir /var/run` non-fatal (various packaging versions)
        text = text.replace(
            Regex("""rmdir\s+/var/run\b"""),
            "rmdir /var/run 2>/dev/null || true # POCKETLINUX_BASE_FILES_PATCH"
        )
        // Also tolerate `rmdir --ignore-fail-on-non-empty` variants failing
        if (!text.contains("POCKETLINUX_BASE_FILES_PATCH")) {
            text = text.replace(
                Regex("""rmdir\s+--ignore-fail-on-non-empty\s+/var/run\b"""),
                "rmdir --ignore-fail-on-non-empty /var/run 2>/dev/null || true # POCKETLINUX_BASE_FILES_PATCH"
            )
        }
        // If still no patch applied, append a no-op marker + ensure exit 0 path
        if (!text.contains("POCKETLINUX_BASE_FILES_PATCH")) {
            text += "\n# POCKETLINUX_BASE_FILES_PATCH\n" +
                "rmdir /var/run 2>/dev/null || true\n" +
                "[ -e /var/run ] || ln -sf /run /var/run 2>/dev/null || true\n"
        }
        postinst.writeText(text)
        postinst.setExecutable(true, false)
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "softPatchBaseFilesPostinst: ${e.message}")
    }
}

internal fun ContainerRestoreEngine.isOurSudoShim(file: File): Boolean =
    com.sg.linuxgo.restore.isOurSudoShim(file)

fun ContainerRestoreEngine.installSudoShim(rootfs: File, onLog: (String) -> Unit = {}) {
    val shim = """
        #!/bin/sh
        # PocketLinux: under Android PRoot the session should already have root
        # capabilities (proot -0). Real setuid sudo cannot work (NO_NEW_PRIVS).
        while [ ${'$'}# -gt 0 ]; do
          case "${'$'}1" in
            -E|-H|-k|-K|-l|-n|-P|-S|-v|-h|--help|--version) shift ;;
            -u|--user|-g|--group|-p|--prompt|-r|--role|-t|--type|-C|--close-from|-T|--command-timeout|--host|-U)
              shift
              [ ${'$'}# -gt 0 ] && shift
              ;;
            --) shift; break ;;
            -*) shift ;;
            *) break ;;
          esac
        done
        if [ ${'$'}# -eq 0 ]; then
          echo "usage: sudo command [args...]" >&2
          exit 1
        fi
        # Desktop + Terminal both use proot -0 (root caps) so apt/dpkg work.
        # Real setuid elevation is impossible on Android (NO_NEW_PRIVS).
        if [ "${'$'}(id -u)" != "0" ]; then
          echo "sudo: session is not root (id=${'$'}(id -u))." >&2
          echo "Restart the desktop from PocketLinux (or open a new Terminal tab)." >&2
          exit 1
        fi
        export DEBIAN_FRONTEND="${'$'}{DEBIAN_FRONTEND:-noninteractive}"
        # Never force LD_PRELOAD on all sudo commands — breaks coreutils.
        exec "${'$'}@"
    """.trimIndent() + "\n"

    val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
    File(localBin, "sudo").writeText(shim)
    File(localBin, "sudo").setExecutable(true, false)

    val binSudo = File(rootfs, "usr/bin/sudo")
    val binSudoReal = File(rootfs, "usr/bin/sudo.real")
    try {
        if (binSudo.exists() && !binSudoReal.exists() && !isOurSudoShim(binSudo)) {
            binSudo.renameTo(binSudoReal)
        }
        binSudo.writeText(shim)
        binSudo.setExecutable(true, false)
        onLog("✓ Configured sudo shim")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "Could not replace /usr/bin/sudo: ${e.message}")
        onLog("! Could not replace /usr/bin/sudo: ${e.message}")
    }
}

/**
 * Content for a minimal [exo-open] when Alpine only has exo-libs (no community/exo).
 * XFCE panel "Terminal Emulator" runs `exo-open --launch TerminalEmulator`.
 */
internal fun buildExoOpenShimScript(): String = """
    #!/bin/sh
    # PocketLinux: minimal exo-open (Alpine may lack package "exo")
    case "${'$'}1" in
      --launch)
        shift
        app="${'$'}1"; shift || true
        case "${'$'}app" in
          TerminalEmulator|Terminal)
            if [ -x /usr/bin/xfce4-terminal ]; then exec /usr/bin/xfce4-terminal "${'$'}@"; fi
            if [ -x /usr/bin/x-terminal-emulator ]; then exec /usr/bin/x-terminal-emulator "${'$'}@"; fi
            if [ -x /usr/bin/xterm ]; then exec /usr/bin/xterm "${'$'}@"; fi
            echo "PocketLinux: no terminal emulator found" >&2
            exit 1
            ;;
          FileManager)
            if [ -x /usr/bin/thunar ]; then exec /usr/bin/thunar "${'$'}@"; fi
            if [ -x /usr/bin/Thunar ]; then exec /usr/bin/Thunar "${'$'}@"; fi
            if [ -x /usr/bin/pcmanfm ]; then exec /usr/bin/pcmanfm "${'$'}@"; fi
            if [ -x /usr/bin/pcmanfm-qt ]; then exec /usr/bin/pcmanfm-qt "${'$'}@"; fi
            exit 1
            ;;
          WebBrowser)
            if [ -x /usr/bin/firefox ]; then exec /usr/bin/firefox "${'$'}@"; fi
            if [ -x /usr/local/bin/firefox ]; then exec /usr/local/bin/firefox "${'$'}@"; fi
            if [ -x /usr/bin/chromium ]; then exec /usr/bin/chromium "${'$'}@"; fi
            exit 1
            ;;
          *)
            if [ -x /usr/bin/xdg-open ]; then exec /usr/bin/xdg-open "${'$'}@"; fi
            exit 1
            ;;
        esac
        ;;
      *)
        if [ -x /usr/bin/xdg-open ]; then exec /usr/bin/xdg-open "${'$'}@"; fi
        exit 1
        ;;
    esac
    """.trimIndent() + "\n"

/**
 * Debian/Ubuntu XFCE "Terminal Emulator" (exo preferred app) runs
 * `/usr/bin/x-terminal-emulator` via update-alternatives. That is a symlink
 * chain:
 *   /usr/bin/x-terminal-emulator → /etc/alternatives/x-terminal-emulator
 *   → /usr/bin/xfce4-terminal.wrapper
 *
 * Alpine often installs exo-libs without package "exo", so `/usr/bin/exo-open`
 * is missing and the panel reports "Failed to execute command exo-open".
 *
 * Toybox backup/restore can drop or leave broken guest absolute symlinks on a
 * second device while the real `xfce4-terminal` binary remains (Applications
 * menu still works). Recreate the chain + pin XFCE helpers.rc to xfce4-terminal.
 */
fun ContainerRestoreEngine.ensureDefaultTerminalEmulator(
    rootfs: File,
    onLog: (String) -> Unit = {}
) {
    try {
        val candidates = listOf(
            "usr/bin/xfce4-terminal.wrapper",
            "usr/bin/xfce4-terminal",
            "usr/bin/xterm",
            "usr/bin/lxterminal",
            "usr/bin/mate-terminal",
            "usr/bin/qterminal"
        )
        val realRel = candidates.firstOrNull { File(rootfs, it).isFile } ?: return
        val realGuestAbs = "/$realRel"
        val helperId = when {
            realRel.contains("xfce4-terminal") -> "xfce4-terminal"
            realRel.contains("mate-terminal") -> "mate-terminal"
            realRel.contains("qterminal") -> "qterminal"
            realRel.contains("lxterminal") -> "lxterminal"
            else -> "xfce4-terminal"
        }

        /** Resolve a path that may be absolute-in-guest or relative, never host /usr. */
        fun guestPath(from: File, target: String): File {
            if (target.startsWith("/")) {
                return File(rootfs, target.removePrefix("/"))
            }
            var cur: File? = from.parentFile ?: rootfs
            for (part in target.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> cur = cur?.parentFile ?: rootfs
                    else -> cur = File(cur ?: rootfs, part)
                }
            }
            return cur ?: rootfs
        }

        /** True if [link] is a real file, or a symlink whose first hop exists under rootfs. */
        fun guestLinkOk(link: File): Boolean {
            val path = link.toPath()
            val isLink = try {
                Files.isSymbolicLink(path)
            } catch (_: Exception) {
                false
            }
            if (!isLink) return link.isFile
            return try {
                val hop = guestPath(link, Files.readSymbolicLink(path).toString())
                hop.isFile || Files.isSymbolicLink(hop.toPath())
            } catch (_: Exception) {
                false
            }
        }

        fun forceSymlink(link: File, target: String) {
            try {
                if (Files.isSymbolicLink(link.toPath()) || link.exists()) {
                    link.delete()
                }
            } catch (_: Exception) {
            }
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        }

        val alt = File(rootfs, "etc/alternatives/x-terminal-emulator")
        val xte = File(rootfs, "usr/bin/x-terminal-emulator")
        var fixed = false

        if (!guestLinkOk(alt)) {
            forceSymlink(alt, realGuestAbs)
            fixed = true
        }
        if (!guestLinkOk(xte)) {
            // Prefer alternatives chain (matches dpkg); fall back to direct binary.
            forceSymlink(xte, "/etc/alternatives/x-terminal-emulator")
            if (!guestLinkOk(xte)) {
                forceSymlink(xte, realGuestAbs)
            }
            fixed = true
        }

        // Alpine (and partial installs): panel needs exo-open --launch TerminalEmulator
        val exoOpen = File(rootfs, "usr/bin/exo-open")
        val exoLocal = File(rootfs, "usr/local/bin/exo-open")
        val exoIsLink = try {
            Files.isSymbolicLink(exoOpen.toPath())
        } catch (_: Exception) {
            false
        }
        val exoPresent = exoOpen.isFile || (exoIsLink && guestLinkOk(exoOpen)) || exoLocal.isFile
        if (!exoPresent) {
            try {
                exoLocal.parentFile?.mkdirs()
                exoLocal.writeText(buildExoOpenShimScript())
                exoLocal.setExecutable(true, false)
                // Prefer PATH-visible /usr/bin for desktop launchers
                if (!exoOpen.isFile && !exoIsLink) {
                    try {
                        forceSymlink(exoOpen, "/usr/local/bin/exo-open")
                    } catch (_: Exception) {
                        exoOpen.writeText(buildExoOpenShimScript())
                        exoOpen.setExecutable(true, false)
                    }
                }
                fixed = true
                onLog("✓ Installed exo-open shim (XFCE preferred apps / panel terminal)")
            } catch (e: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "exo-open shim: ${e.message}")
            }
        }

        // System-wide helpers so exo finds TerminalEmulator without per-user config
        try {
            File(rootfs, "etc/xdg/xfce4").mkdirs()
            val sysHelpers = File(rootfs, "etc/xdg/xfce4/helpers.rc")
            if (!sysHelpers.isFile) {
                val fm = when {
                    File(rootfs, "usr/bin/thunar").isFile ||
                        File(rootfs, "usr/bin/Thunar").isFile -> "thunar"
                    File(rootfs, "usr/bin/pcmanfm").isFile -> "pcmanfm"
                    else -> null
                }
                sysHelpers.writeText(
                    buildString {
                        if (fm != null) appendLine("FileManager=$fm")
                        appendLine("TerminalEmulator=$helperId")
                    }
                )
                fixed = true
            } else {
                val lines = sysHelpers.readLines().toMutableList()
                var changed = false
                if (lines.none { it.startsWith("TerminalEmulator=") }) {
                    lines.add("TerminalEmulator=$helperId")
                    changed = true
                }
                if (changed) {
                    sysHelpers.writeText(lines.joinToString("\n").trimEnd() + "\n")
                    fixed = true
                }
            }
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "system helpers.rc: ${e.message}")
        }

        // XFCE preferred-apps: seed TerminalEmulator only when missing.
        // Never force-overwrite a user choice on every installProotHelpers call
        // (that path runs often and must not fight user customizations).
        val homes = mutableListOf(File(rootfs, "etc/skel"), File(rootfs, "root"))
        File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.let { homes.addAll(it) }
        for (home in homes) {
            try {
                val cfgDir = File(home, ".config/xfce4").apply { mkdirs() }
                val helpers = File(cfgDir, "helpers.rc")
                val lines = if (helpers.isFile) {
                    helpers.readLines().toMutableList()
                } else {
                    mutableListOf()
                }
                val sawTerm = lines.any { it.startsWith("TerminalEmulator=") }
                val sawFm = lines.any { it.startsWith("FileManager=") }
                var changed = false
                if (!sawTerm) {
                    lines.add("TerminalEmulator=$helperId")
                    changed = true
                }
                // Prefer Thunar (XFCE default) over pcmanfm to avoid "choose file manager" prompts
                if (!sawFm) {
                    val fm = when {
                        File(rootfs, "usr/bin/thunar").isFile ||
                            File(rootfs, "usr/bin/Thunar").isFile -> "thunar"
                        File(rootfs, "usr/bin/pcmanfm").isFile -> "pcmanfm"
                        File(rootfs, "usr/bin/pcmanfm-qt").isFile -> "pcmanfm-qt"
                        else -> null
                    }
                    if (fm != null) {
                        lines.add("FileManager=$fm")
                        changed = true
                    }
                }
                if (changed || !helpers.isFile) {
                    helpers.writeText(lines.joinToString("\n").trimEnd() + "\n")
                }
            } catch (e: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "helpers.rc ${home.name}: ${e.message}")
            }
        }

        if (fixed) {
            onLog("✓ Restored default Terminal Emulator → $realGuestAbs")
            Log.i(ContainerRestoreEngine.TAG, "ensureDefaultTerminalEmulator → $realGuestAbs")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureDefaultTerminalEmulator: ${e.message}")
    }
}

/** Host helpers directory (legacy; nested proot path no longer used). */
