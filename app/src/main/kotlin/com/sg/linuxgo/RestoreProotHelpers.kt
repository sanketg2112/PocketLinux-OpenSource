package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** PRoot helpers, wallpaper, local desktop compat. */

fun ContainerRestoreEngine.installProotHelpers(rootfs: File, onLog: (String) -> Unit = {}) {
    // Deploy link_shim so dpkg can create status-old (hardlink) on Android.
    deployLinkShim(rootfs, onLog)
    // Hide pcmanfm-qt / Thunar "Root Instance" banners under proot -0.
    deployUidSpoof(rootfs, onLog)
    // Deploy high-quality pocketlinux wallpaper icon
    deployWallpaper(rootfs, onLog)
    ensureXfceWallpaperAndThunarDefaults(rootfs, onLog)
    installFileManagerUidWrappers(rootfs)
    repairDpkgState(rootfs, onLog)
    // Arch absolute xkb symlink breaks host-side Lorie / Wayland compositor.
    ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootfs, onLog)
    // GTK4/glycin/SVG loaders call bwrap (user namespaces) — impossible under
    // Android PRoot. Without a shim, xfce4-panel/libwnck abort → black desktop.
    installFakeBwrap(rootfs, onLog)
    // Heavy LD compat only once (install/restore). Never on every GUI click.
    installLocalDesktopCompat(rootfs, onLog)

    installSudoShim(rootfs, onLog)
    // Ubuntu/Debian: XFCE "Terminal Emulator" needs x-terminal-emulator alternatives.
    ensureDefaultTerminalEmulator(rootfs, onLog)
    val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }

    // dpkg/apt wrappers: preload link_shim ONLY for package managers (status-old hardlinks)
    installDpkgAptWrappers(rootfs, localBin)

    val profileD = File(rootfs, "etc/profile.d").apply { mkdirs() }
    // Login shells start with a clean system PATH (Android host env must not leak).
    // Many tools install only to $HOME/... and patch ~/.bashrc — desktop terminals
    // (non-login) pick that up; app Terminal (bash --login) would miss them without
    // this. Also re-check common dirs on each prompt so `curl | bash` installers
    // work after the next command without manually running source ~/.bashrc.
    File(profileD, "pocketlinux-path.sh").writeText(buildUserPathProfileScript())
    // Identity: detect user if known, otherwise leave placeholders for applySessionIdentity.
    val detected = detectGuestUsername(rootfs)
    if (!detected.isNullOrBlank() && detected != "root") {
        applySessionIdentity(rootfs, detected)
    } else {
        // Minimal identity script; applySessionIdentity rewrites with real user later.
        applySessionIdentity(rootfs, "PocketLinux")
    }
    // Noninteractive apt only — do NOT set global LD_PRELOAD (crashes every binary).
    File(profileD, "pocketlinux-dpkg.sh").writeText(
        """
        export DEBIAN_FRONTEND=noninteractive
        export DEBCONF_NONINTERACTIVE_SEEN=true
        # Clear a broken global preload left by older PocketLinux builds.
        case ":${'$'}{LD_PRELOAD}:" in
          *:/usr/lib/link_shim.so:*)
            LD_PRELOAD=${'$'}(echo "${'$'}LD_PRELOAD" | tr ':' '\n' | grep -v 'link_shim.so' | tr '\n' ':' | sed 's/:$//')
            export LD_PRELOAD
            [ -z "${'$'}LD_PRELOAD" ] && unset LD_PRELOAD
            ;;
        esac
        """.trimIndent() + "\n"
    )

    listOf("tmp", "var/tmp", "run").forEach { rel ->
        val d = File(rootfs, rel)
        d.mkdirs()
        d.setWritable(true, false)
        d.setReadable(true, false)
        d.setExecutable(true, false)
    }
    // Debian base-files expects /var/run → /run (not a real directory).
    ensureVarRunSymlink(rootfs)

    onLog("✓ Configured sudo + dpkg/xbps hardlink shim for PocketLinux")
}

/** Copy bundled link_shim.so into the guest so LD_PRELOAD works for dpkg/apt wrappers. */
internal fun ContainerRestoreEngine.deployLinkShim(rootfs: File, onLog: (String) -> Unit) {
    try {
        val dest = File(rootfs, "usr/lib/link_shim.so")
        dest.parentFile?.mkdirs()
        context.assets.open("link_shim.so").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
        onLog("✓ link_shim.so ready for dpkg wrappers")
        try {
            val xbpsShim = File(rootfs, "usr/lib/xbps_extract_shim.so")
            context.assets.open("xbps_extract_shim.so").use { input ->
                xbpsShim.outputStream().use { output -> input.copyTo(output) }
            }
            xbpsShim.setReadable(true, false)
            xbpsShim.setExecutable(true, false)
            onLog("✓ xbps_extract_shim.so ready for Void package unpack")
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "deploy xbps_extract_shim: ${e.message}")
        }
        try {
            val mat = File(rootfs, "usr/local/bin/pocketlinux-xbps-materialize")
            mat.parentFile?.mkdirs()
            context.assets.open("pocketlinux_xbps_materialize.py").use { input ->
                mat.outputStream().use { output -> input.copyTo(output) }
            }
            mat.setReadable(true, false)
            mat.setExecutable(true, false)
            onLog("✓ xbps soname copy helper ready")
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "deploy xbps materialize: ${e.message}")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "deployLinkShim: ${e.message}")
        onLog("⚠ link_shim.so missing — apt may fail on status-old hardlinks")
    }
}

/**
 * Deploy geteuid/getuid spoof for GUI file managers only.
 * pcmanfm-qt shows a red "Root Instance" label when geteuid()==0 (proot -0).
 * This does not drop real privileges — only libc id queries for those processes.
 */
internal fun ContainerRestoreEngine.deployUidSpoof(rootfs: File, onLog: (String) -> Unit) {
    try {
        val dest = File(rootfs, "usr/lib/pocketlinux_uid_spoof.so")
        dest.parentFile?.mkdirs()
        context.assets.open("pocketlinux_uid_spoof.so").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
        onLog("✓ uid spoof ready (hides file-manager Root Instance banner)")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "deployUidSpoof: ${e.message}")
        onLog("⚠ pocketlinux_uid_spoof.so missing — pcmanfm may show Root Instance")
    }
}

/**
 * Install-time wallpaper seed (also used once for legacy containers missing backdrops).
 * Not a per-boot path — call from bootstrap install / setupDisplayConfig / one-shot repair.
 *
 * - `/usr/share/backgrounds/` and `…/xfce/` — Arch + generic XFCE
 * - `/usr/share/xfce4/backdrops/` — Debian/Ubuntu Desktop Settings default folder
 * - `/usr/share/images/desktop-base/` — Kali/Debian XFCE "desktop-base" folder
 *   (missing dir → "Unable to load images from folder (null)" / ENOENT)
 */
fun ContainerRestoreEngine.deployWallpaper(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        val bytes = context.assets.open("pocketlinux_wp.png").use { it.readBytes() }
        val targets = listOf(
            File(rootfs, "usr/share/backgrounds/pocketlinux_wp.png"),
            File(rootfs, "usr/share/backgrounds/xfce/pocketlinux_wp.png"),
            File(rootfs, "usr/share/xfce4/backdrops/pocketlinux_wp.png"),
            File(rootfs, "usr/share/images/desktop-base/pocketlinux_wp.png"),
        )
        for (dest in targets) {
            dest.parentFile?.mkdirs()
            dest.outputStream().use { it.write(bytes) }
            dest.setReadable(true, false)
        }
        // Empty dirs break XFCE Desktop Settings on open — always create them
        File(rootfs, "usr/share/xfce4/backdrops").mkdirs()
        File(rootfs, "usr/share/images/desktop-base").mkdirs()
        // Convenience alias some settings UIs browse under backgrounds/
        try {
            val link = File(rootfs, "usr/share/backgrounds/desktop-base")
            if (!link.exists()) {
                Files.createSymbolicLink(
                    link.toPath(),
                    Paths.get("/usr/share/images/desktop-base")
                )
            }
        } catch (_: Exception) {
        }
        onLog("✓ pocketlinux_wp.png ready (backgrounds, xfce, backdrops, desktop-base)")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "deployWallpaper: ${e.message}")
        onLog("⚠ pocketlinux_wp.png missing from APK assets")
        // Still create folders so Desktop Settings does not error on open
        try {
            File(rootfs, "usr/share/xfce4/backdrops").mkdirs()
            File(rootfs, "usr/share/images/desktop-base").mkdirs()
        } catch (_: Exception) {
        }
    }
}

/**
 * One-shot repair for Kali/Debian XFCE:
 * - Fix backdrop last-image when it points at a missing file (black wallpaper)
 * - Prefer Thunar as default file manager (helpers.rc + mimeapps.list)
 *
 * Safe to re-run when marker version bumps; does not thrash on every GUI click.
 */
fun ContainerRestoreEngine.ensureXfceWallpaperAndThunarDefaults(
    rootfs: File,
    onLog: (String) -> Unit = {}
) {
    val marker = File(rootfs, "var/lib/pocketlinux/xfce_wp_thunar_v1")
    if (marker.isFile) return
    try {
        deployWallpaper(rootfs) { /* quiet seed */ }

        // Prefer official Kali cubism wallpaper when the guest installer seeded it
        val wpGuest = sequenceOf(
            "usr/share/xfce4/backdrops/kali-cubism.jpg",
            "usr/share/backgrounds/kali-cubism.jpg",
            "usr/share/images/desktop-base/kali-cubism.jpg",
            "usr/share/xfce4/backdrops/pocketlinux_wp.png",
            "usr/share/backgrounds/pocketlinux_wp.png",
            "usr/share/backgrounds/xfce/pocketlinux_wp.png",
            "usr/share/images/desktop-base/pocketlinux_wp.png",
        ).firstOrNull { File(rootfs, it).isFile }?.let { "/$it" }
            ?: "/usr/share/xfce4/backdrops/pocketlinux_wp.png"

        val thunarDesktop = sequenceOf(
            "usr/share/applications/thunar.desktop",
            "usr/share/applications/org.xfce.thunar.desktop",
            "usr/share/applications/Thunar.desktop",
        ).firstOrNull { File(rootfs, it).isFile }?.substringAfterLast('/')
            ?: "thunar.desktop"

        val hasThunar = File(rootfs, "usr/bin/thunar").isFile ||
            File(rootfs, "usr/bin/Thunar").isFile

        val homes = mutableListOf(File(rootfs, "etc/skel"), File(rootfs, "root"))
        File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.let { homes.addAll(it) }

        for (home in homes) {
            try {
                // helpers.rc → Thunar
                if (hasThunar) {
                    val helpers = File(home, ".config/xfce4/helpers.rc")
                    helpers.parentFile?.mkdirs()
                    val lines = if (helpers.isFile) helpers.readLines().toMutableList() else mutableListOf()
                    val withoutFm = lines.filterNot { it.startsWith("FileManager=") }.toMutableList()
                    withoutFm.add("FileManager=thunar")
                    if (withoutFm.none { it.startsWith("TerminalEmulator=") }) {
                        withoutFm.add("TerminalEmulator=xfce4-terminal")
                    }
                    helpers.writeText(withoutFm.joinToString("\n").trimEnd() + "\n")
                }

                // mimeapps.list → Thunar for folders
                if (hasThunar) {
                    val mime = File(home, ".config/mimeapps.list")
                    mime.parentFile?.mkdirs()
                    val body = buildString {
                        appendLine("[Default Applications]")
                        appendLine("inode/directory=$thunarDesktop")
                        appendLine("inode/mount-point=$thunarDesktop")
                        appendLine("x-scheme-handler/trash=$thunarDesktop")
                    }
                    if (!mime.isFile) {
                        mime.writeText(body)
                    } else {
                        var text = mime.readText()
                        // Force folder MIME to Thunar (overwrite stale pcmanfm defaults)
                        text = text.replace(
                            Regex("""(?m)^inode/directory=.*$"""),
                            "inode/directory=$thunarDesktop"
                        )
                        if (!text.contains("inode/directory=")) {
                            text = if (text.contains("[Default Applications]")) {
                                text.replace(
                                    "[Default Applications]",
                                    "[Default Applications]\ninode/directory=$thunarDesktop"
                                )
                            } else {
                                body + "\n" + text
                            }
                        }
                        mime.writeText(text)
                    }
                }

                // Fix backdrop XML when last-image path is missing → black desktop
                val deskXml = File(
                    home,
                    ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
                )
                if (deskXml.isFile) {
                    var xml = deskXml.readText()
                    val lastImageRe = Regex(
                        """(<property name="last-image" type="string" value=")([^"]*)("/>)"""
                    )
                    var changed = false
                    xml = lastImageRe.replace(xml) { m ->
                        val path = m.groupValues[2]
                        val exists = path.isNotBlank() &&
                            File(rootfs, path.removePrefix("/")).isFile
                        if (!exists) {
                            changed = true
                            "${m.groupValues[1]}$wpGuest${m.groupValues[3]}"
                        } else {
                            m.value
                        }
                    }
                    // Ensure zoom style when we rewrote any path
                    if (changed) {
                        xml = xml.replace(
                            Regex("""(<property name="image-style" type="int" value=")0("/>)""")
                        ) { m ->
                            "${m.groupValues[1]}5${m.groupValues[2]}"
                        }
                        deskXml.writeText(xml)
                    }
                } else if (hasThunar || File(rootfs, "usr/bin/xfdesktop").isFile) {
                    // Seed a working default if user never got skel desktop xml
                    deskXml.parentFile?.mkdirs()
                    deskXml.writeText(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <channel name="xfce4-desktop" version="1.0">
                          <property name="desktop-icons" type="empty">
                            <property name="style" type="int" value="0"/>
                          </property>
                          <property name="backdrop" type="empty">
                            <property name="screen0" type="empty">
                              <property name="monitor0" type="empty">
                                <property name="workspace0" type="empty">
                                  <property name="color-style" type="int" value="0"/>
                                  <property name="image-style" type="int" value="5"/>
                                  <property name="last-image" type="string" value="$wpGuest"/>
                                </property>
                              </property>
                              <property name="monitorVirtual-0" type="empty">
                                <property name="workspace0" type="empty">
                                  <property name="color-style" type="int" value="0"/>
                                  <property name="image-style" type="int" value="5"/>
                                  <property name="last-image" type="string" value="$wpGuest"/>
                                </property>
                              </property>
                            </property>
                          </property>
                        </channel>
                        """.trimIndent() + "\n"
                    )
                }
            } catch (e: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "xfce defaults ${home.name}: ${e.message}")
            }
        }

        // System-wide defaults (new sessions / exo)
        if (hasThunar) {
            try {
                File(rootfs, "etc/xdg").mkdirs()
                File(rootfs, "etc/xdg/mimeapps.list").writeText(
                    """
                    [Default Applications]
                    inode/directory=$thunarDesktop
                    inode/mount-point=$thunarDesktop
                    x-scheme-handler/trash=$thunarDesktop
                    """.trimIndent() + "\n"
                )
                File(rootfs, "etc/xdg/xfce4").mkdirs()
                File(rootfs, "etc/xdg/xfce4/helpers.rc").writeText(
                    "FileManager=thunar\nTerminalEmulator=xfce4-terminal\n"
                )
            } catch (e: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "system mime/helpers: ${e.message}")
            }
        }

        marker.parentFile?.mkdirs()
        marker.writeText("ok\n")
        onLog("✓ XFCE wallpaper + Thunar defaults applied")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureXfceWallpaperAndThunarDefaults: ${e.message}")
    }
}

/**
 * Port of Local Desktop setup stages that keep XFCE/Arch stable under Android PRoot:
 *  P0 — disable ssh/gpg agents; hide power-manager / tumbler autostart
 *  P1 — Firefox autoconfig (sandbox off); tumblerd kill-switch desktop
 *  P2 — Onboard signal.set_wakeup_fd fix; fake /proc + empty SELinux dir for binds
 */
fun ContainerRestoreEngine.installLocalDesktopCompat(rootfs: File, onLog: (String) -> Unit = {}) {
    // One-shot marker: full rewrite is expensive; only refresh if missing/outdated.
    // v4 = SaveOnExit=false + compositing off + hide Firefox autostart (Arch exit 137).
    val marker = File(rootfs, "var/lib/pocketlinux/ld_compat_v4")
    if (marker.isFile) return
    installFirefoxAutoconfig(rootfs, onLog)
    installOnboardSignalFix(rootfs, onLog)
    hardenXfceSessionAndAutostart(rootfs, onLog)
    maskTumbler(rootfs, onLog)
    // Fake /proc files optional (no longer bound every launch — speed)
    writeFakeProcSys(rootfs, onLog)
    try {
        marker.parentFile?.mkdirs()
        File(rootfs, "var/lib/pocketlinux/ld_compat_v3").delete()
        marker.writeText("ok\n")
    } catch (_: Exception) {
    }
}

/**
 * Minimal work for every GUI start: bwrap + FM wrappers if missing.
 * Does not rewrite sudo, dpkg shims, or full LD compat.
 */
