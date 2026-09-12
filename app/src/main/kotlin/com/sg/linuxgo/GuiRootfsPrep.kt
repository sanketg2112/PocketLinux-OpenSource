package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.PointerIcon
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.sg.linuxgo.x11.ICmdEntryInterface
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.Prefs
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** Arch host X11 deps and GUI rootfs preparation. */

fun GuiSessionManager.ensureArchX11HostDeps(rootFsDir: File) {
    val isArch = try {
        File(rootFsDir, "etc/os-release").takeIf { it.isFile }?.readText()
            ?.contains("arch", ignoreCase = true) == true
    } catch (_: Exception) {
        false
    }
    if (!isArch) return

    val already = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootFsDir) { }
    if (already != null) {
        // Still fix absolute symlink quietly if needed
        return
    }

    val needPulse = !File(rootFsDir, "usr/bin/pactl").isFile
    onLog("Arch: installing host X11 deps (xkeyboard-config" +
        (if (needPulse) ", pulseaudio…" else "…") + ") — one-time, needs network")
    activity.runOnUiThread {
        viewDelegate.showLoadingStatus("Installing XKB (Arch)…")
    }

    // PRoot rootfs often has unowned/leftover files → pacman "conflicting files".
    // Install XKB alone first (critical), then pulse optional. Always --overwrite.
    // ${'$'} avoids Kotlin string-template collisions with shell variables.
    val d = "${'$'}"
    val pulsePkgs = if (needPulse) "pulseaudio pulseaudio-alsa libpulse" else ""
    val cmd = """
        set +e
        export LANG=C
        if ! command -v pacman >/dev/null 2>&1; then
          echo "! pacman not found"
          exit 1
        fi
        mkdir -p /var/tmp 2>/dev/null || true
        if [ ! -d /var/lib/pacman/sync ] || [ -z "${d}(ls -A /var/lib/pacman/sync 2>/dev/null)" ]; then
          echo "pacman -Sy (sync DBs)…"
          pacman -Sy --noconfirm --noprogressbar --disable-download-timeout
        fi

        # --- critical: xkeyboard-config alone (host Lorie cannot start without it) ---
        echo "pacman -S --needed --overwrite xkeyboard-config"
        # Log full conflict details (not piped through tail — need real exit code)
        pacman -S --noconfirm --needed --noprogressbar --disable-download-timeout \
          --overwrite '*' xkeyboard-config
        ec_xkb=${d}?
        echo "xkeyboard-config pacman exit=${d}ec_xkb"
        if [ "${d}ec_xkb" != "0" ]; then
          echo "! xkeyboard-config install failed — retrying after -Sdd (skip deps)"
          pacman -S --noconfirm --needed --noprogressbar --disable-download-timeout \
            --overwrite '*' --nodeps --nodeps xkeyboard-config
          ec_xkb=${d}?
          echo "xkeyboard-config -Sdd exit=${d}ec_xkb"
        fi

        # --- optional: pulse (separate so XKB is not blocked by audio conflicts) ---
        if [ -n "$pulsePkgs" ] && [ ! -x /usr/bin/pactl ]; then
          echo "pacman -S --needed --overwrite $pulsePkgs"
          pacman -S --noconfirm --needed --noprogressbar --disable-download-timeout \
            --overwrite '*' $pulsePkgs
          echo "pulse packages exit=${d}?"
        fi

        # Absolute X11/xkb → relative (host Lorie cannot follow /usr/share/… on Android)
        if [ -L /usr/share/X11/xkb ]; then
          _t=${d}(readlink /usr/share/X11/xkb 2>/dev/null || true)
          case "${d}_t" in
            /*)
              if [ -d /usr/share/xkeyboard-config-2 ]; then
                ln -sfn ../xkeyboard-config-2 /usr/share/X11/xkb
                echo "Fixed absolute XKB symlink → ../xkeyboard-config-2"
              elif [ -d /usr/share/xkeyboard-config ]; then
                ln -sfn ../xkeyboard-config /usr/share/X11/xkb
                echo "Fixed absolute XKB symlink → ../xkeyboard-config"
              fi
              ;;
          esac
          unset _t
        fi
        # If package put a real tree but classic path is missing, create relative link
        if [ ! -e /usr/share/X11/xkb ] && [ -d /usr/share/xkeyboard-config-2 ]; then
          mkdir -p /usr/share/X11
          ln -sfn ../xkeyboard-config-2 /usr/share/X11/xkb
          echo "Created X11/xkb → ../xkeyboard-config-2"
        fi
        echo "— XKB tree —"
        ls -la /usr/share/X11/xkb /usr/share/xkeyboard-config-2 /usr/share/xkeyboard-config 2>&1 | head -20
        if [ -d /usr/share/xkeyboard-config-2/rules ] || [ -d /usr/share/X11/xkb/rules ]; then
          echo "✓ XKB rules present"
          exit 0
        fi
        echo "! still no XKB rules/"
        # Last-ditch: show who owns /usr/share/X11 if anything
        pacman -Qo /usr/share/X11/xkb 2>&1 || true
        pacman -Qo /usr/share/xkeyboard-config-2 2>&1 || true
        exit 1
    """.trimIndent()

    try {
        val runner = ProotRunner(activity, { line ->
            // Surface install progress + errors (conflicts, overwrites, paths)
            if (line.startsWith("!") ||
                line.startsWith("✓") ||
                line.startsWith("—") ||
                line.startsWith("pacman") ||
                line.contains("Fixed") ||
                line.contains("Created") ||
                line.contains("xkeyboard") ||
                line.contains("error", ignoreCase = true) ||
                line.contains("conflict", ignoreCase = true) ||
                line.contains("exists in filesystem", ignoreCase = true) ||
                line.contains("installing", ignoreCase = true) ||
                line.contains("downloading", ignoreCase = true) ||
                line.contains("Packages (") ||
                line.contains("exit=")
            ) {
                onLog(line)
            }
        }, ProotBinary.Purpose.SETUP)
        val code = runner.executeCommand(rootFsDir.absolutePath, cmd)
        val xkb = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootFsDir) { msg ->
            if (msg.startsWith("Fixed") || msg.startsWith("!") || msg.startsWith("⚠")) onLog(msg)
        }
        if (xkb != null) {
            onLog("✓ Arch XKB ready for host: $xkb")
        } else {
            onLog("! Arch XKB still missing after pacman (exit=$code).")
            onLog("  Try in Terminal: pacman -S --noconfirm --overwrite '*' xkeyboard-config")
        }
    } catch (e: Exception) {
        onLog("! ensureArchX11HostDeps failed: ${e.message}")
        android.util.Log.e("GuiSessionManager", "ensureArchX11HostDeps", e)
    }
}

/**
 * Install guest packages required for XFCE Wayland (nested labwc).
 * X11 install scripts (legacy + prebuilt) do not ship labwc / Xwayland / wlr-randr / swaybg.
 * Called from [prepareWaylandSession] on first Wayland boot when experimental Wayland is on.
 * One-shot when any required binary is missing; needs network.
 */
fun GuiSessionManager.ensureWaylandGuestDeps(rootFsDir: File) {
    val osRelease = try {
        File(rootFsDir, "etc/os-release").takeIf { it.isFile }?.readText().orEmpty()
    } catch (_: Exception) {
        ""
    }
    val isArch = osRelease.contains("arch", ignoreCase = true)
    val isDebianFamily = osRelease.contains("debian", ignoreCase = true) ||
        osRelease.contains("ubuntu", ignoreCase = true) ||
        osRelease.contains("kali", ignoreCase = true) ||
        File(rootFsDir, "etc/debian_version").isFile

    val needLabwc = !File(rootFsDir, "usr/bin/labwc").isFile
    val needXwayland = !File(rootFsDir, "usr/bin/Xwayland").isFile
    val needWlr = !File(rootFsDir, "usr/bin/wlr-randr").isFile
    val needSwaybg = !File(rootFsDir, "usr/bin/swaybg").isFile
    // Debian Xwayland needs libtirpc; partial apt under proot often leaves it missing
    val needTirpc = isDebianFamily && sequenceOf(
        "usr/lib/aarch64-linux-gnu/libtirpc.so.3",
        "lib/aarch64-linux-gnu/libtirpc.so.3",
        "usr/lib/libtirpc.so.3"
    ).none { File(rootFsDir, it).isFile }
    if (!needLabwc && !needXwayland && !needWlr && !needSwaybg && !needTirpc) {
        onLog("✓ Wayland deps present (labwc, Xwayland, wlr-randr, swaybg)")
        return
    }

    activity.runOnUiThread {
        viewDelegate.showLoadingStatus("Installing Wayland packages…")
    }

    val d = "${'$'}"
    val cmd = when {
        isArch -> {
            val pkgs = buildList {
                if (needLabwc) add("labwc")
                if (needXwayland) add("xorg-xwayland")
                if (needWlr) add("wlr-randr")
                if (needSwaybg) add("swaybg")
                add("xdg-desktop-portal")
                add("xdg-desktop-portal-gtk")
            }.joinToString(" ")
            onLog("Arch Wayland: installing $pkgs (one-time, needs network)…")
            """
                set +e
                export LANG=C
                if ! command -v pacman >/dev/null 2>&1; then
                  echo "! pacman not found"
                  exit 1
                fi
                mkdir -p /var/tmp 2>/dev/null || true
                echo "pacman -Sy (refresh package DBs)…"
                pacman -Sy --noconfirm --noprogressbar --disable-download-timeout
                echo "pacman -S --needed --overwrite $pkgs"
                pacman -S --noconfirm --needed --noprogressbar --disable-download-timeout \
                  --overwrite '*' $pkgs
                ec=${d}?
                echo "wayland pkgs pacman exit=${d}ec"
                if [ ! -x /usr/bin/labwc ] || [ ! -x /usr/bin/Xwayland ]; then
                  echo "Retry: pacman -Syy then reinstall…"
                  pacman -Syy --noconfirm --noprogressbar --disable-download-timeout
                  pacman -S --noconfirm --needed --noprogressbar --disable-download-timeout \
                    --overwrite '*' $pkgs
                  echo "wayland pkgs retry exit=${d}?"
                fi
                if [ ! -x /usr/bin/labwc ]; then
                  echo "! labwc still missing after install"
                  exit 1
                fi
                if [ ! -x /usr/bin/Xwayland ]; then
                  echo "! Xwayland still missing after install"
                  exit 1
                fi
                echo "✓ Arch Wayland packages ready"
                ls -la /usr/bin/labwc /usr/bin/Xwayland /usr/bin/wlr-randr /usr/bin/swaybg 2>&1 | head -10
                exit 0
            """.trimIndent()
        }
        isDebianFamily -> {
            // Debian/Ubuntu: xwayland (not xorg-xwayland); libtirpc required by Xwayland
            // First Wayland boot only — X11 install scripts intentionally omit these.
            val pkgs = buildList {
                if (needLabwc) add("labwc")
                if (needXwayland) add("xwayland")
                if (needWlr) add("wlr-randr")
                if (needSwaybg) add("swaybg")
                // Xwayland needs libtirpc.so.3 (Debian 13 / Ubuntu 24.04: libtirpc3t64)
                add("libtirpc3t64")
                add("xdg-desktop-portal")
                add("xdg-desktop-portal-gtk")
            }.joinToString(" ")
            onLog("Debian/Ubuntu Wayland (first boot): installing $pkgs…")
            """
                set +e
                export LANG=C
                export DEBIAN_FRONTEND=noninteractive
                if ! command -v apt-get >/dev/null 2>&1; then
                  echo "! apt-get not found"
                  exit 1
                fi
                mkdir -p /var/lib/dpkg /var/tmp /tmp
                chmod 1777 /tmp /var/tmp 2>/dev/null || true
                for f in status-old diversions-old statoverride-old available-old; do
                  touch /var/lib/dpkg/${d}f 2>/dev/null || true
                  chmod 666 /var/lib/dpkg/${d}f 2>/dev/null || true
                done
                chmod u+w /var/lib/dpkg/status 2>/dev/null || true
                # labwc/wlr-randr live in universe on Ubuntu — ensure components enabled
                if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
                  sed -i 's/Components: .*/Components: main restricted universe multiverse/g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
                fi
                if [ -f /etc/apt/sources.list ]; then
                  sed -i 's/ main${d}/ main restricted universe multiverse${d}/g' /etc/apt/sources.list 2>/dev/null || true
                  sed -i 's/ main / main restricted universe multiverse /g' /etc/apt/sources.list 2>/dev/null || true
                fi
                echo "apt-get update…"
                apt-get update -y 2>&1 | tail -20
                echo "apt-get install -y $pkgs"
                apt-get install -y --no-install-recommends \
                  -o Dpkg::Options::="--force-confdef" \
                  -o Dpkg::Options::="--force-confold" \
                  $pkgs 2>&1
                ec=${d}?
                echo "wayland pkgs apt exit=${d}ec"
                dpkg --configure -a 2>&1 | tail -10 || true

                # PRoot often breaks dpkg (EPERM on status-old). Fallback: download .debs
                # and extract with dpkg-deb -x (no postinst / status rewrite).
                extract_debs() {
                  mkdir -p /tmp/pl-debs
                  cd /tmp/pl-debs || return 1
                  rm -f ./*.deb 2>/dev/null
                  for pkg in ${d}@; do
                    echo "apt-get download ${d}pkg"
                    apt-get download "${d}pkg" 2>&1 | tail -8
                  done
                  for deb in ./*.deb; do
                    [ -f "${d}deb" ] || continue
                    echo "dpkg-deb -x ${d}deb /"
                    dpkg-deb -x "${d}deb" / 2>&1 || true
                  done
                  ldconfig 2>/dev/null || true
                  cd /
                }
                if [ ! -x /usr/bin/labwc ] || [ ! -x /usr/bin/Xwayland ]; then
                  echo "Fallback extract: labwc xwayland wlr-randr swaybg"
                  extract_debs labwc xwayland wlr-randr swaybg
                fi
                if ! ls /usr/lib/*/libtirpc.so* /lib/*/libtirpc.so* 2>/dev/null | grep -q .; then
                  echo "Fallback extract: libtirpc3t64"
                  extract_debs libtirpc3t64
                  if ! ls /usr/lib/*/libtirpc.so* /lib/*/libtirpc.so* 2>/dev/null | grep -q .; then
                    extract_debs libtirpc3
                  fi
                fi
                if [ ! -x /usr/bin/labwc ]; then
                  echo "! labwc still missing after install (startxfce4 --wayland needs labwc)"
                  exit 1
                fi
                if [ ! -x /usr/bin/Xwayland ]; then
                  echo "! Xwayland still missing after install"
                  exit 1
                fi
                echo "✓ Debian/Ubuntu Wayland packages ready (first boot)"
                ls -la /usr/bin/labwc /usr/bin/Xwayland /usr/bin/wlr-randr /usr/bin/swaybg 2>&1 | head -10
                ls /usr/lib/*/libtirpc.so* /lib/*/libtirpc.so* 2>/dev/null | head -5
                exit 0
            """.trimIndent()
        }
        else -> {
            onLog("! Wayland deps missing and distro is not Arch/Debian — install labwc + Xwayland manually")
            return
        }
    }

    try {
        val runner = ProotRunner(activity, { line ->
            if (line.startsWith("!") ||
                line.startsWith("✓") ||
                line.startsWith("pacman") ||
                line.startsWith("apt") ||
                line.contains("error", ignoreCase = true) ||
                line.contains("conflict", ignoreCase = true) ||
                line.contains("installing", ignoreCase = true) ||
                line.contains("downloading", ignoreCase = true) ||
                line.contains("Packages (") ||
                line.contains("exit=") ||
                line.contains("still missing") ||
                line.contains("Setting up") ||
                line.contains("Unpacking")
            ) {
                onLog(line)
            }
        }, ProotBinary.Purpose.SETUP)
        val code = runner.executeCommand(rootFsDir.absolutePath, cmd)
        if (File(rootFsDir, "usr/bin/labwc").isFile) {
            onLog("✓ Wayland deps installed (exit=$code)")
        } else {
            onLog("! Wayland deps incomplete (exit=$code). Install labwc + Xwayland in Terminal.")
        }
    } catch (e: Exception) {
        onLog("! ensureWaylandGuestDeps failed: ${e.message}")
        android.util.Log.e("GuiSessionManager", "ensureWaylandGuestDeps", e)
    }
}

/** @deprecated Use [ensureWaylandGuestDeps] — kept as alias for call sites. */
fun GuiSessionManager.ensureArchWaylandDeps(rootFsDir: File) = ensureWaylandGuestDeps(rootFsDir)

/**
 * Full prep for native Wayland: rootfs helpers, host Pulse, XKB, guest packages, launch scripts.
 * Runs on a background thread before [WaylandActivity] starts.
 */
fun GuiSessionManager.prepareWaylandSession(rootFsDir: File) {
    bootstrap.overrideGuiMode("wayland")
    // Host compositor binds wayland-0 under rootfs/tmp — must exist & be world-writable
    try {
        val tmp = File(rootFsDir, "tmp")
        if (!tmp.isDirectory) {
            tmp.delete()
            tmp.mkdirs()
        }
        tmp.setReadable(true, false)
        tmp.setWritable(true, false)
        tmp.setExecutable(true, false)
        // Sticky world-writable like a real /tmp (best-effort on Android)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "1777", tmp.absolutePath)).waitFor()
        } catch (_: Exception) {
        }
        File(tmp, "pocketlinux-desktop-ready").delete()
        File(activity.filesDir, "containers/active_rootfs/tmp/pocketlinux-desktop-ready").delete()
        onLog("Wayland rootfs /tmp ready: ${tmp.absolutePath}")
    } catch (e: Exception) {
        onLog("! Could not prepare rootfs /tmp: ${e.message}")
    }
    prepareGuiRootfs(rootFsDir)

    // Always rewrite launch helpers for Wayland so GUI_MODE + localdesktop.toml match
    // (X11 containers keep a cached launch script that would otherwise stay on x11).
    try {
        onLog("Rewriting guest launch helpers for Wayland…")
        bootstrap.setupDisplayConfig(rootFsDir)
        onLog("✓ Wayland launch helpers written")
    } catch (e: Exception) {
        onLog("! Wayland setupDisplayConfig failed: ${e.message}")
        android.util.Log.e("GuiSessionManager", "prepareWaylandSession display", e)
    }

    // Compositor reads keymaps from active_rootfs; Arch absolute XKB symlink breaks that
    ensureArchX11HostDeps(rootFsDir)
    ensureWaylandGuestDeps(rootFsDir)

    // tawc-compat: guest profile (toolkit backends, XWayland, optional hybris paths)
    try {
        TawcWaylandCompat.installGuestProfile(rootFsDir, activity)
        val hybris = LibhybrisInstaller.prepare(activity, rootFsDir)
        onLog(
            "tawc-compat Wayland: gfx=${TawcWaylandCompat.graphicsBackend(activity)} " +
                "gtk3_menus=${TawcWaylandCompat.isGtk3MenusWorkaroundEnabled(activity)} " +
                "libhybris=$hybris"
        )
    } catch (e: Exception) {
        onLog("! tawc-compat guest profile: ${e.message}")
    }

    try {
        val paOk = HostPulseAudioServer.ensureRunning(activity) { onLog(it) }
        if (!paOk) onLog("! Host PulseAudio failed to start (Wayland audio may be silent)")
    } catch (e: Exception) {
        onLog("! Host PulseAudio: ${e.message}")
    }
}

fun GuiSessionManager.prepareGuiRootfs(rootFsDir: File) {
    ArchPacmanSecurity.applySecureMirrorlist(rootFsDir)
    repairArtixGlibcIfNeeded(rootFsDir)
    // FAST PATH: marker present → only cheap DNS + XKB resolve + session-cache wipe.
    // Heavy rewrites (launch script, Firefox wrap, Thunar wrap) are one-shot.
    // Doing them every boot was freezing/force-closing the app (read of large
    // browser binaries, multi‑KB script regen, proot helpers).
    bootstrap.setupDNS(rootFsDir)

    val isArch = com.sg.linuxgo.gui.isArchRootfs(rootFsDir)
    val launch = File(rootFsDir, "usr/local/bin/pocketlinux-launch")
    val legacyLaunch = File(rootFsDir, "root/launch.sh")
    val miniSession = File(rootFsDir, "usr/local/bin/pocketlinux-xfce-session")
    // v44 = overlay waits for xfdesktop, not just xfce4-panel.
    val launchVer = File(rootFsDir, "var/lib/pocketlinux/launch_script_v44")
    val archHardened = File(rootFsDir, "var/lib/pocketlinux/arch_x11_harden_v18")
    val gpuModeStamp = File(rootFsDir, "var/lib/pocketlinux/gpu_driver_mode")
    val launchDeStamp = File(rootFsDir, "var/lib/pocketlinux/launch_de")
    val desktopDeStamp = File(rootFsDir, "var/lib/pocketlinux/desktop_de")
    val configuredGpu = bootstrap.getConfiguredGpuDriverMode()
    val resolvedGpu = bootstrap.resolveGpuDriverModeForRootfs(rootFsDir, configuredGpu)
    val stampedGpu = gpuModeStamp.takeIf { it.isFile }?.readText()?.trim().orEmpty()
    val desiredDe = DesktopDeConversion.normalizeDe(bootstrap.selectedDE)
    val stampedLaunchDe = launchDeStamp.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        .let { DesktopDeConversion.normalizeDe(it) }
    val stampedDesktopDe = desktopDeStamp.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        .let { DesktopDeConversion.normalizeDe(it) }

    // Cheap existence checks only — never readText() multi‑MB binaries here.
    // Launch script is small (tens of KB); safe to read when present for DE match.
    val miniOk = !isArch || desiredDe == "lxqt" || desiredDe == "mate" ||
        (miniSession.isFile && miniSession.length() in 200L..50_000L &&
            !fileLooksLikeOldBrowserBlock(miniSession))
    val launchText = try {
        if (launch.isFile && launch.length() in 500L..500_000L) launch.readText() else ""
    } catch (_: Exception) {
        ""
    }
    val launchMatchesDe = if (desiredDe.isBlank() || launchText.isEmpty()) {
        true
    } else {
        try {
            DesktopDeConversion.launchScriptMatchesDe(launchText, desiredDe)
        } catch (_: Exception) {
            false
        }
    }
    val deStampMismatch = desiredDe.isNotBlank() && (
        (stampedLaunchDe.isNotBlank() && stampedLaunchDe != desiredDe) ||
            (stampedDesktopDe.isNotBlank() && stampedDesktopDe != desiredDe) ||
            !launchMatchesDe
        )
    // Stamp can match while launch still embeds Zink from an older bake (glmark2 bug).
    val gpuLaunchConflict = launchText.isNotEmpty() &&
        com.sg.linuxgo.bootstrap.launchScriptGpuConflictsWithMode(launchText, resolvedGpu)
    // Full rewrite: missing version stamp, missing launch, GPU mode change,
    // Zink/Freedreno conflict in baked script, or DE mismatch after convert.
    val needLaunchRewrite =
        !launch.isFile || launch.length() < 500L ||
            !legacyLaunch.isFile ||
            !launchVer.isFile ||
            !miniOk ||
            stampedGpu != resolvedGpu ||
            gpuLaunchConflict ||
            deStampMismatch

    if (needLaunchRewrite) {
        onLog(
            when {
                deStampMismatch ->
                    "Refreshing launch helpers for desktop ${DesktopDeConversion.labelForDe(desiredDe)}…"
                !launchVer.isFile ->
                    "Migrating existing container launch helpers (v44) → $resolvedGpu…"
                gpuLaunchConflict ->
                    "Fixing GPU bake-in (prefs=$resolvedGpu; clearing stale Zink/llvmpipe in launch)…"
                stampedGpu != resolvedGpu ->
                    "Refreshing guest launch helpers for GPU mode $resolvedGpu…"
                else ->
                    "Rewriting guest launch helpers…"
            }
        )
        try {
            if (!LibhybrisRuntime.shouldApply(activity, rootFsDir)) {
                bootstrap.setupGpuEnvConfig(rootFsDir, configuredGpu)
            }
            LibhybrisInstaller.prepare(activity, rootFsDir)
            bootstrap.setupDisplayConfig(rootFsDir)
            launchVer.parentFile?.mkdirs()
            listOf(
                "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10",
                "v11", "v12", "v13", "v14", "v15", "v16", "v17", "v18",
                "v19", "v20", "v21", "v22", "v23", "v24", "v25", "v26", "v27", "v28",
                "v29", "v30", "v31", "v32", "v33", "v34", "v35", "v36", "v37", "v38", "v39", "v40", "v41", "v42", "v43"
            ).forEach { v ->
                File(rootFsDir, "var/lib/pocketlinux/launch_script_$v").delete()
            }
            launchVer.writeText("ok\n")
            if (desiredDe.isNotBlank()) {
                launchDeStamp.writeText("$desiredDe\n")
                desktopDeStamp.writeText("$desiredDe\n")
            }
        } catch (e: Exception) {
            onLog("! launch rewrite failed: ${e.message}")
            android.util.Log.e("GuiSessionManager", "launch rewrite", e)
        }
    } else {
        // Env files only — launch already matches stamp + v28.
        try {
            if (!LibhybrisRuntime.shouldApply(activity, rootFsDir)) {
                bootstrap.setupGpuEnvConfig(rootFsDir, configuredGpu)
            }
            LibhybrisInstaller.prepare(activity, rootFsDir)
        } catch (e: Exception) {
            android.util.Log.w("GuiSessionManager", "setupGpuEnvConfig: ${e.message}")
        }
    }

    try {
        val engine = ContainerRestoreEngine(activity)
        try {
            val hasXbps = File(rootFsDir, "usr/bin/xbps-install").isFile ||
                File(rootFsDir, "usr/bin/xbps-install.real").isFile
            if (hasXbps) {
                engine.deployLinkShim(rootFsDir) { /* quiet */ }
                installXbpsWrappersOnto(
                    rootFsDir,
                    File(rootFsDir, "usr/local/bin").apply { mkdirs() },
                )
                onLog("✓ Void xbps unpack helper for package installs")
            }
        } catch (_: Exception) {
        }
        // All distros: intentional Log Out helpers (must write user_logout + wrap /usr/bin).
        try {
            val logoutStamp = File(rootFsDir, "var/lib/pocketlinux/logout_helpers_v3")
            val logoutHelper = File(rootFsDir, "usr/local/bin/pocketlinux-logout")
            val usrWrap = File(rootFsDir, "usr/bin/xfce4-session-logout")
            val logoutText = try {
                if (logoutHelper.isFile) logoutHelper.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val usrText = try {
                if (usrWrap.isFile) usrWrap.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val needLogout =
                !logoutStamp.isFile ||
                    !logoutHelper.isFile ||
                    !logoutText.contains("user_logout") ||
                    !usrText.contains("PocketLinux")
            if (needLogout) {
                engine.ensureDesktopLogoutHelpers(rootFsDir) { msg ->
                    if (msg.startsWith("✓") || msg.startsWith("!")) onLog(msg)
                }
            }
        } catch (_: Exception) {
        }
        // Wallpaper is install-time only (setupDisplayConfig + guest install script).
        // One-shot repair for containers installed before xfce4/backdrops was seeded.
        val wpBackdropsOk = File(rootFsDir, "var/lib/pocketlinux/wallpaper_backdrops_v1")
        val backdropsWp = File(rootFsDir, "usr/share/xfce4/backdrops/pocketlinux_wp.png")
        val desktopBaseWp = File(rootFsDir, "usr/share/images/desktop-base/pocketlinux_wp.png")
        val kaliWp = File(rootFsDir, "usr/share/xfce4/backdrops/kali-cubism.jpg")
        // Kali installer seeds kali-cubism + marker; do not re-seed brand art over it.
        if (
            !wpBackdropsOk.isFile ||
            (!backdropsWp.isFile && !kaliWp.isFile) ||
            (!desktopBaseWp.isFile && !File(rootFsDir, "usr/share/images/desktop-base/kali-cubism.jpg").isFile)
        ) {
            try {
                engine.deployWallpaper(rootFsDir) { /* quiet */ }
                wpBackdropsOk.parentFile?.mkdirs()
                wpBackdropsOk.writeText("ok\n")
            } catch (_: Exception) {
            }
        }
        // One-shot: fix black wallpaper (missing last-image) + Thunar as default FM
        try {
            engine.ensureXfceWallpaperAndThunarDefaults(rootFsDir) { /* quiet */ }
        } catch (_: Exception) {
        }
        // Cheap every boot: wipe session restore files (tiny dirs only).
        engine.wipeXfceSavedSessions(rootFsDir) { /* quiet */ }
        File(rootFsDir, "tmp/pocketlinux-block-heavy").delete()

        // One-shot Arch harden (Firefox hard-wrap, mini-session, Thunar, xfce xml).
        // Mini-session is XFCE-only — never reinstall it after convert to MATE/LXQt.
        val wantArchXfceMini = desiredDe.isBlank() || desiredDe == "xfce4" || desiredDe == "xfce"
        if (isArch && !archHardened.isFile) {
            onLog("One-shot Arch X11 harden…")
            engine.ensureGuiCriticalShims(rootFsDir) { onLog(it) }
            if (wantArchXfceMini) {
                engine.hardenXfceSessionAndAutostart(rootFsDir) { msg ->
                    if (msg.startsWith("✓") || msg.startsWith("!")) onLog(msg)
                }
            }
            engine.installFirefoxWrapper(rootFsDir) { onLog(it) }
            engine.installFirefoxAutoconfig(rootFsDir) { onLog(it) }
            if (wantArchXfceMini) {
                engine.repairArchThunarWrappers(rootFsDir) { onLog(it) }
                engine.ensureArchXfceMiniSession(rootFsDir) { onLog(it) }
            }
            engine.wrapArchFirefoxRealBinary(rootFsDir) { onLog(it) }
            try {
                archHardened.parentFile?.mkdirs()
                archHardened.writeText("ok\n")
                File(rootFsDir, "var/lib/pocketlinux/arch_x11_harden_v15").delete()
                File(rootFsDir, "var/lib/pocketlinux/arch_x11_harden_v16").delete()
                File(rootFsDir, "var/lib/pocketlinux/arch_x11_harden_v17").delete()
            } catch (_: Exception) {
            }
            onLog("✓ Arch X11 harden complete (cached for next boots)")
        } else if (isArch) {
            // Ensure mini-session exists without rewriting Firefox every time.
            // Rewrite when missing logout-v1 (panel death → exit so Android returns Home).
            val miniText = try {
                if (miniSession.isFile) miniSession.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val needsMiniRewrite = wantArchXfceMini && (
                !miniSession.isFile || miniSession.length() < 200L ||
                    !miniText.contains("user_logout") ||
                    !miniText.contains("mini_session_v9") ||
                    miniText.contains("panel gone") ||
                    !File(rootFsDir, "usr/local/bin/pocketlinux-logout").isFile
                )
            if (needsMiniRewrite) {
                engine.ensureArchXfceMiniSession(rootFsDir) { onLog(it) }
            }
            // Cheap: re-seed Firefox AutoConfig if package manager overwrote install tree
            val ffCfg = File(rootFsDir, "usr/lib/firefox/pocketlinux.cfg")
            if (!ffCfg.isFile || !ffCfg.readText().contains("firefox_proot_v7")) {
                engine.installFirefoxAutoconfig(rootFsDir) { /* quiet */ }
                engine.installFirefoxWrapper(rootFsDir) { /* quiet */ }
                engine.wrapArchFirefoxRealBinary(rootFsDir) { /* quiet */ }
            }
        } else if (!archHardened.isFile) {
            // Non-Arch: still one-shot Firefox soft profile if never done
            engine.ensureGuiCriticalShims(rootFsDir) { onLog(it) }
            engine.installFirefoxWrapper(rootFsDir) { /* quiet */ }
            engine.installFirefoxAutoconfig(rootFsDir) { /* quiet */ }
            try {
                archHardened.parentFile?.mkdirs()
                archHardened.writeText("ok\n")
            } catch (_: Exception) {
            }
        }

        // XFCE mini-session for all distros (tawcroot prefers it; Arch already has it).
        if (wantArchXfceMini) {
            val miniText = try {
                if (miniSession.isFile) miniSession.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val needsMini = !miniSession.isFile || miniSession.length() < 200L ||
                !miniText.contains("user_logout") ||
                !miniText.contains("mini_session_v9")
            if (needsMini) {
                engine.ensureArchXfceMiniSession(rootFsDir) { msg ->
                    if (msg.startsWith("✓") || msg.startsWith("!")) onLog(msg)
                }
            }
        }
        // Alpine + tawcroot: ensure host static apk is inside the rootfs for apk ops.
        if (TawcrootAlpineSupport.isAlpineRootfs(rootFsDir)) {
            try {
                val rt = ProotBinary.resolve(activity, ProotBinary.Purpose.DESKTOP)
                if (rt.useTawcroot) {
                    TawcrootAlpineSupport.stageApkStatic(activity, rootFsDir)
                }
            } catch (_: Exception) {
            }
        }

        val xkb = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootFsDir) { msg ->
            if (msg.startsWith("Fixed") || msg.startsWith("!")) onLog(msg)
        }
        if (xkb != null) {
            onLog("XKB_CONFIG_ROOT=$xkb")
        }
        val userFile = File(rootFsDir, "etc/pocketlinux/username")
        if (!userFile.isFile || userFile.readText().isBlank()) {
            val user = bootstrap.resolveUsernameForRootfs(rootFsDir)
            engine.applySessionIdentity(rootFsDir, user)
        }
    } catch (e: Exception) {
        android.util.Log.w("GuiSessionManager", "prepareGuiRootfs: ${e.message}")
        onLog("! prepareGuiRootfs: ${e.message}")
    }
    repairBinSh(rootFsDir)
}

/** Peek small scripts only — never load multi‑MB ELF into a String. */
internal fun GuiSessionManager.fileLooksLikeOldBrowserBlock(file: File): Boolean =
    com.sg.linuxgo.gui.fileLooksLikeOldBrowserBlock(file)

