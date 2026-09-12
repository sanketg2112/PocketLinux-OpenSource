package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** GUI shims: firefox, onboard, xfce, tumbler, bwrap. */

fun ContainerRestoreEngine.ensureGuiCriticalShims(rootfs: File, onLog: (String) -> Unit = {}) {
    val bwrap = File(rootfs, "usr/local/bin/bwrap")
    if (!bwrap.isFile || bwrap.length() < 50L) {
        installFakeBwrap(rootfs, onLog)
    }
    val binSudo = File(rootfs, "usr/bin/sudo")
    val localSudo = File(rootfs, "usr/local/bin/sudo")
    if (!binSudo.isFile || !isOurSudoShim(binSudo) || !localSudo.isFile || !isOurSudoShim(localSudo)) {
        installSudoShim(rootfs, onLog)
    }
    var needsFmFix = false
    val apps = listOf(
        "pcmanfm-qt" to "/usr/bin/pcmanfm-qt",
        "pcmanfm" to "/usr/bin/pcmanfm",
        "thunar" to "/usr/bin/thunar",
        "Thunar" to "/usr/bin/Thunar"
    )
    // Broken Arch wrappers: /usr/sbin/Thunar → missing Thunar.real
    for (orphan in listOf("usr/sbin/Thunar", "usr/sbin/thunar")) {
        val f = File(rootfs, orphan)
        if (f.isFile && f.length() < 8000L) {
            try {
                if (f.readText().contains(".real")) {
                    needsFmFix = true
                    break
                }
            } catch (_: Exception) {
            }
        }
    }
    if (!needsFmFix) {
        for ((_, real) in apps) {
            val binFile = File(rootfs, real.removePrefix("/"))
            if (binFile.isFile) {
                var isWrapper = false
                try {
                    if (binFile.length() < 5000L) {
                        val t = binFile.readText()
                        if (t.contains("PocketLinux file-manager wrapper") &&
                            !t.contains("basename")
                        ) {
                            isWrapper = true
                        }
                    }
                } catch (_: Exception) {}
                if (!isWrapper) {
                    needsFmFix = true
                    break
                }
            }
        }
    }
    if (needsFmFix) {
        installFileManagerUidWrappers(rootfs)
    }
    // uid spoof: needed by PulseAudio (refuses getuid()==0) and file managers
    val spoof = File(rootfs, "usr/lib/pocketlinux_uid_spoof.so")
    if (!spoof.isFile || spoof.length() < 100L) {
        deployUidSpoof(rootfs, onLog)
    }
    // Firefox profile: re-apply when marker missing/outdated
    try {
        val ffMarker = File(rootfs, "var/lib/pocketlinux/firefox_proot_v7")
        val cfg = File(rootfs, "usr/lib/firefox/pocketlinux.cfg")
        val needsFf =
            !ffMarker.isFile ||
                (cfg.isFile && !cfg.readText().contains("firefox_proot_v7")) ||
                File(rootfs, "usr/bin/firefox").isFile ||
                File(rootfs, "usr/lib/firefox").isDirectory ||
                File(rootfs, "usr/lib/firefox-esr").isDirectory
        if (needsFf) {
            installFirefoxAutoconfig(rootfs, onLog)
            installFirefoxWrapper(rootfs, onLog)
            ffMarker.parentFile?.mkdirs()
            ffMarker.writeText("ok\n")
            File(rootfs, "var/lib/pocketlinux/firefox_proot_v3").delete()
            File(rootfs, "var/lib/pocketlinux/firefox_proot_v4").delete()
            File(rootfs, "var/lib/pocketlinux/firefox_proot_v5").delete()
            File(rootfs, "var/lib/pocketlinux/firefox_proot_v6").delete()
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureFirefox: ${e.message}")
    }
    // Arch: ensure pactl exists (Debian images already ship pulseaudio-utils)
    try {
        val isArch = File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText()
            ?.contains("arch", ignoreCase = true) == true
        val pactl = File(rootfs, "usr/bin/pactl")
        if (isArch && !pactl.isFile) {
            val tip = File(rootfs, "var/lib/pocketlinux/need_pulse_utils")
            tip.parentFile?.mkdirs()
            tip.writeText("pacman -S --needed pulseaudio pulseaudio-alsa ffmpeg\n")
            onLog("⚠ Arch: install audio/codecs once: pacman -S --needed pulseaudio pulseaudio-alsa ffmpeg")
        }
        // Arch: host Lorie needs a readable XKB tree or X0 never appears
        if (isArch) {
            val xkb = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootfs) { msg ->
                if (msg.startsWith("Fixed") || msg.startsWith("!") || msg.startsWith("⚠")) onLog(msg)
            }
            if (xkb == null) {
                onLog("! Arch: missing XKB — run in Terminal: pacman -S --needed xkeyboard-config")
                onLog("  then: ln -sfn ../xkeyboard-config-2 /usr/share/X11/xkb  (if still absolute)")
            }
        }
    } catch (_: Exception) {
    }
    // Flag missing feh so launch script can one-shot install (early image wallpaper).
    try {
        val fehBin = File(rootfs, "usr/bin/feh")
        val marker = File(rootfs, "var/lib/pocketlinux/need_feh")
        if (!fehBin.isFile) {
            marker.parentFile?.mkdirs()
            marker.writeText("1\n")
        } else {
            marker.delete()
        }
    } catch (_: Exception) {
    }
    // Ubuntu/Debian restore: panel "Terminal Emulator" → x-terminal-emulator
    ensureDefaultTerminalEmulator(rootfs, onLog)
}

/** Fake /proc + /sys files for ProotRunner to bind over Android host paths (Local Desktop). */
fun ContainerRestoreEngine.writeFakeProcSys(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        val proc = File(rootfs, "proc").apply { mkdirs() }
        val sysEmpty = File(rootfs, "sys/.empty").apply { mkdirs() }
        // Mode 0700 on these dirs is intentional (LD does the same under proot).
        proc.setExecutable(true, false)
        File(rootfs, "sys").setExecutable(true, false)
        sysEmpty.setExecutable(true, false)

        val files = mapOf(
            "proc/.loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "proc/.stat" to
                "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
                "cpu0 31 0 226 12027 82 10 4 9 0 0\n",
            "proc/.uptime" to "124.08 932.80\n",
            "proc/.version" to
                "Linux version 6.2.1 (proot@pocketlinux) (gcc (GCC) 12.2.1) " +
                "#1 SMP PREEMPT_DYNAMIC Wed, 01 Mar 2023 00:00:00 +0000\n",
            "proc/.vmstat" to
                "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\n" +
                "nr_zone_active_anon 7183\n",
            "proc/.sysctl_entry_cap_last_cap" to "40\n",
            "proc/.sysctl_inotify_max_user_watches" to "4096\n"
        )
        for ((rel, content) in files) {
            val f = File(rootfs, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        onLog("✓ Fake /proc + /sys/.empty ready for host binds")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "writeFakeProcSys: ${e.message}")
        onLog("⚠ Fake /proc setup incomplete: ${e.message}")
    }
}

/** Paths relative to rootfs that ProotRunner should bind over host /proc|/sys. */
fun ContainerRestoreEngine.fakeProcSysBinds(rootfs: File): Map<String, String> {
    val out = linkedMapOf<String, String>()
    fun add(rel: String, guest: String) {
        val host = File(rootfs, rel)
        if (host.exists()) out[host.absolutePath] = guest
    }
    add("proc/.loadavg", "/proc/loadavg")
    add("proc/.stat", "/proc/stat")
    add("proc/.uptime", "/proc/uptime")
    add("proc/.version", "/proc/version")
    add("proc/.vmstat", "/proc/vmstat")
    add("proc/.sysctl_entry_cap_last_cap", "/proc/sys/kernel/cap_last_cap")
    add("proc/.sysctl_inotify_max_user_watches", "/proc/sys/fs/inotify/max_user_watches")
    add("sys/.empty", "/sys/fs/selinux")
    return out
}

/**
 * Firefox under PRoot: sandbox off + video/memory limits.
 * Video playback otherwise OOM-kills the whole proot tree (exit 137).
 * First line of the cfg file must be a comment (Mozilla requirement).
 *
 * Sandbox must stay disabled under PRoot. Recent Firefox (Arch rolling) shows a
 * non-dismissible infobar when effectiveContentSandboxLevel==0. Debian often
 * ships older ESR without that UI. userChrome cannot reliably hide it (Shadow
 * DOM), so we no-op SandboxUtils via unsandboxed AutoConfig JS.
 */
fun ContainerRestoreEngine.installFirefoxAutoconfig(rootfs: File, onLog: (String) -> Unit = {}) {
    // Keep close to the Debian+XFCE profile that already works for video+audio.
    val cfgBody = FIREFOX_POCKETLINUX_CFG
    val autoJs = FIREFOX_AUTOCONFIG_JS
    try {
        val candidates = listOf(
            "usr/lib/firefox",
            "usr/lib/firefox-esr",
            "usr/lib64/firefox"
        )
        var wrote = false
        for (baseRel in candidates) {
            val base = File(rootfs, baseRel)
            if (!base.isDirectory && !File(rootfs, "usr/bin/firefox").exists()) continue
            val root = if (base.isDirectory) base else File(rootfs, "usr/lib/firefox")
            writeFirefoxAutoconfigTree(root, autoJs, cfgBody)
            wrote = true
        }
        if (!wrote) {
            writeFirefoxAutoconfigTree(File(rootfs, "usr/lib/firefox"), autoJs, cfgBody)
        }
        // Also cover ESR path if present and not already in candidates loop
        File(rootfs, "usr/lib/firefox-esr").takeIf { it.isDirectory }?.let { root ->
            writeFirefoxAutoconfigTree(root, autoJs, cfgBody)
        }
        onLog("✓ Firefox proot profile (sandbox off, AutoConfig no-op banner, Pulse, soft GL)")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "installFirefoxAutoconfig: ${e.message}")
        onLog("⚠ Firefox autoconfig skipped: ${e.message}")
    }
}

private fun writeFirefoxAutoconfigTree(root: File, autoJs: String, cfgBody: String) {
    File(root, "defaults/pref").mkdirs()
    File(root, "defaults/pref/autoconfig.js").writeText(autoJs)
    File(root, "pocketlinux.cfg").writeText(cfgBody)
}

/**
 * Unsandboxed AutoConfig entry — required so pocketlinux.cfg may use ChromeUtils
 * to patch SandboxUtils (hides non-dismissible sandbox-disabled infobar).
 */
internal val FIREFOX_AUTOCONFIG_JS =
    """
    pref("general.config.filename", "pocketlinux.cfg");
    pref("general.config.obscure_value", 0);
    pref("general.config.sandbox_enabled", false);
    """.trimIndent() + "\n"

/**
 * pocketlinux.cfg — prefs + early JS patch of SandboxUtils warning functions.
 * First line MUST be a comment (Mozilla AutoConfig requirement).
 */
internal val FIREFOX_POCKETLINUX_CFG =
    """
    // PocketLinux firefox_proot_v7 — sandbox off under PRoot; no-op disabled-sandbox infobar
    defaultPref("media.cubeb.sandbox", false);
    defaultPref("media.cubeb.backend", "pulse");
    defaultPref("security.sandbox.content.level", 0);
    defaultPref("security.sandbox.gpu.level", 0);
    defaultPref("security.sandbox.warn_unprivileged_namespaces", false);
    defaultPref("media.ffmpeg.vaapi.enabled", false);
    defaultPref("gfx.webrender.force-disabled", true);
    defaultPref("layers.acceleration.disabled", true);
    defaultPref("browser.cache.memory.enable", false);
    defaultPref("fission.autostart", false);
    defaultPref("dom.ipc.processCount", 1);
    defaultPref("dom.ipc.processCount.webIsolated", 1);
    defaultPref("webgl.force-enabled", true);
    defaultPref("webgl.disabled", false);
    defaultPref("webgl.out-of-process", false);

    // Arch ships newer Firefox that shows a permanent "security sandbox is disabled"
    // bar when effectiveContentSandboxLevel===0. Debian ESR often lacks this UI.
    // Patch SandboxUtils so the bar never appears (userChrome cannot pierce Shadow DOM).
    (function () {
      function patch() {
        try {
          var mod = ChromeUtils.importESModule(
            "resource://gre/modules/SandboxUtils.sys.mjs"
          );
          if (mod && mod.SandboxUtils) {
            mod.SandboxUtils.maybeWarnAboutDisabledContentSandbox = function () {};
            mod.SandboxUtils.maybeWarnAboutMissingUserNamespaces = function () {};
            mod.SandboxUtils._sandboxDisabledThisSession = false;
          }
        } catch (e1) {
          try {
            // Older ESR path (if present)
            var legacy = ChromeUtils.import(
              "resource://gre/modules/SandboxUtils.jsm"
            );
            if (legacy && legacy.SandboxUtils) {
              legacy.SandboxUtils.maybeWarnAboutDisabledContentSandbox = function () {};
              legacy.SandboxUtils.maybeWarnAboutMissingUserNamespaces = function () {};
            }
          } catch (e2) {}
        }
      }
      patch();
      try {
        var Services = ChromeUtils.importESModule(
          "resource://gre/modules/Services.sys.mjs"
        ).Services;
        function stripAndPatch() {
          patch();
          try {
            var wins = Services.wm.getEnumerator("navigator:browser");
            while (wins.hasMoreElements()) {
              var win = wins.getNext();
              try {
                var box = win.gNotificationBox;
                if (!box) continue;
                var n = box.getNotificationWithValue("sandbox-content-disabled");
                if (n) box.removeNotification(n);
                n = box.getNotificationWithValue("sandbox-unprivileged-namespaces");
                if (n) box.removeNotification(n);
              } catch (e3) {}
            }
          } catch (e4) {}
        }
        [
          "final-ui-startup",
          "browser-delayed-startup-finished",
          "sessionstore-windows-restored",
        ].forEach(function (topic) {
          try {
            Services.obs.addObserver(
              { observe: function () { stripAndPatch(); } },
              topic,
              false
            );
          } catch (e5) {}
        });
      } catch (e6) {}
    })();
    """.trimIndent() + "\n"

/** Host-side /usr/local/bin/firefox wrapper — software GL + sandbox env + boot block. */
fun ContainerRestoreEngine.installFirefoxWrapper(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        val real = listOf("usr/bin/firefox", "usr/lib/firefox/firefox", "usr/lib/firefox-esr/firefox")
            .map { File(rootfs, it) }
            .firstOrNull { it.isFile }
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val wrapper = File(binDir, "firefox")
        wrapper.writeText(firefoxWrapperScriptBody())
        wrapper.setReadable(true, false)
        wrapper.setExecutable(true, false)
        if (real != null) {
            onLog("✓ Firefox wrapper → software GL / low-RAM media")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "installFirefoxWrapper: ${e.message}")
    }
}

/**
 * Arch .desktop files use Exec=/usr/lib/firefox/firefox — bypasses PATH wrappers.
 * Move the real binary to firefox.real and put our wrapper at the absolute path.
 *
 * NEVER use readText() on multi‑MB ELF binaries (was force-closing the Android app).
 */
fun ContainerRestoreEngine.wrapArchFirefoxRealBinary(rootfs: File, onLog: (String) -> Unit = {}) {
    fun peekIsShellScript(f: File): Boolean {
        if (!f.isFile) return false
        // Shell wrappers are tiny; ELF browsers are multi‑MB
        if (f.length() > 32_000L) return false
        return try {
            f.inputStream().use { ins ->
                val buf = ByteArray(2)
                val n = ins.read(buf)
                n >= 2 && buf[0] == '#'.code.toByte() && buf[1] == '!'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun readWrapHead(f: File): String? {
        if (!peekIsShellScript(f)) return null
        return try {
            f.inputStream().bufferedReader().use { r ->
                val sb = StringBuilder(800)
                var i = 0
                while (i < 800) {
                    val ch = r.read()
                    if (ch == -1) break
                    sb.append(ch.toChar())
                    i++
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isOurWrapAny(head: String?) =
        head != null && head.contains("PocketLinux firefox hard-wrap")

    fun isCurrentWrap(head: String?) =
        head != null && head.contains("PocketLinux firefox hard-wrap v7")

    val candidates = listOf(
        "usr/lib/firefox/firefox",
        "usr/lib/firefox-esr/firefox",
        "usr/lib64/firefox/firefox"
    )
    for (rel in candidates) {
        val bin = File(rootfs, rel)
        if (!bin.isFile) continue
        try {
            val real = File(rootfs, "$rel.real")
            val head = readWrapHead(bin)
            if (isCurrentWrap(head) && real.isFile) {
                // Already current generation — skip rewrite on every boot
                continue
            }
            if (isOurWrapAny(head) && !real.isFile) {
                // Broken wrap without .real — leave alone
                onLog("! /$rel is a wrapper but .real missing — skip")
                continue
            }
            if (isOurWrapAny(head) && real.isFile) {
                // Upgrade old PocketLinux wrapper in place (ELF already at .real)
                bin.writeText(firefoxWrapperScriptBody(hardWrap = true))
                bin.setReadable(true, false)
                bin.setExecutable(true, false)
                onLog("✓ Upgraded hard-wrap /$rel (sandbox banner hide)")
                continue
            }
            if (!real.exists()) {
                // Never rename a shell script over the real binary slot
                if (peekIsShellScript(bin)) {
                    onLog("! /$rel is an unknown script — skip hard-wrap")
                    continue
                }
                // Move ELF → .real (rename only; never copy multi‑MB into RAM as text)
                if (!bin.renameTo(real)) {
                    // Fallback stream copy without loading whole file as String
                    real.outputStream().use { out ->
                        bin.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    bin.delete()
                }
            }
            if (!real.isFile) {
                onLog("! could not stage $rel.real")
                continue
            }
            bin.writeText(firefoxWrapperScriptBody(hardWrap = true))
            bin.setReadable(true, false)
            bin.setExecutable(true, false)
            onLog("✓ Hard-wrapped /$rel (soft-GL via desktop Exec= path)")
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "wrapArchFirefoxRealBinary $rel: ${e.message}")
        }
    }
    // Chromium: same rules — never readText() multi‑MB binaries
    for (rel in listOf("usr/lib/chromium/chromium", "usr/lib/chromium-browser/chromium-browser")) {
        val bin = File(rootfs, rel)
        if (!bin.isFile) continue
        try {
            val real = File(rootfs, "$rel.real")
            if (peekIsShellScript(bin)) continue
            if (bin.length() < 100_000L) continue // not a real browser binary
            if (!real.exists()) {
                if (!bin.renameTo(real)) {
                    real.outputStream().use { out ->
                        bin.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    bin.delete()
                }
            }
            if (!real.isFile) continue
            bin.writeText(
                """
                #!/bin/sh
                # PocketLinux chromium hard-wrap — software path under PRoot
                exec $rel.real --no-sandbox "${'$'}@"
                """.trimIndent() + "\n"
            )
            bin.setExecutable(true, false)
            onLog("✓ Hard-wrapped /$rel")
        } catch (_: Exception) {
        }
    }
}

internal fun ContainerRestoreEngine.firefoxWrapperScriptBody(hardWrap: Boolean = false): String {
    val execBlock = if (hardWrap) {
        """
        if [ -x "${'$'}0.real" ]; then
            exec "${'$'}0.real" "${'$'}@"
        elif [ -x /usr/lib/firefox/firefox.real ]; then
            exec /usr/lib/firefox/firefox.real "${'$'}@"
        elif [ -x /usr/lib/firefox-esr/firefox.real ]; then
            exec /usr/lib/firefox-esr/firefox.real "${'$'}@"
        else
            echo "firefox.real not found" >&2
            exit 1
        fi
        """.trimIndent()
    } else {
        """
        if [ -x /usr/lib/firefox/firefox.real ]; then
            exec /usr/lib/firefox/firefox.real "${'$'}@"
        elif [ -x /usr/lib/firefox/firefox ] && ! head -1 /usr/lib/firefox/firefox 2>/dev/null | grep -q '^#!'; then
            exec /usr/lib/firefox/firefox "${'$'}@"
        elif [ -x /usr/lib/firefox-esr/firefox.real ]; then
            exec /usr/lib/firefox-esr/firefox.real "${'$'}@"
        elif [ -x /usr/lib/firefox-esr/firefox ] && ! head -1 /usr/lib/firefox-esr/firefox 2>/dev/null | grep -q '^#!'; then
            exec /usr/lib/firefox-esr/firefox "${'$'}@"
        elif [ -x /usr/bin/firefox ] && ! head -1 /usr/bin/firefox 2>/dev/null | grep -q '^#!'; then
            exec /usr/bin/firefox "${'$'}@"
        else
            echo "firefox not found" >&2
            exit 1
        fi
        """.trimIndent()
    }
    return """
        #!/bin/sh
        # PocketLinux firefox hard-wrap v7 — software GL / low-RAM under Android PRoot.
        # Desktop .desktop files call /usr/lib/firefox/firefox directly (not PATH).
        # Banner hide is via AutoConfig (pocketlinux.cfg) patching SandboxUtils — not CSS.
        # No launch-time block — user may open Firefox anytime after desktop is up.
        export MOZ_DISABLE_CONTENT_SANDBOX=1
        export MOZ_FAKE_NO_SANDBOX=1
        export MOZ_DISABLE_RDD_SANDBOX=1
        export MOZ_GL_ALWAYS_SOFTWARE=1
        export MOZ_WEBRENDER=0
        export MOZ_ACCELERATED=0
        export MOZ_ENABLE_WAYLAND=0
        export MOZ_WEBGL_FORCE_OPENGL=1
        export GDK_BACKEND=x11
        export GDK_GL=disable
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        export MESA_LOADER_DRIVER_OVERRIDE=swrast
        export MESA_DEBUG=silent
        if [ -z "${'$'}PULSE_SERVER" ]; then
            export PULSE_SERVER=tcp:127.0.0.1:14713
        fi
        $execBlock
    """.trimIndent() + "\n"
}

/**
 * Onboard: proot fstat on socket fds makes Python signal.set_wakeup_fd fail (Local Desktop).
 */
fun ContainerRestoreEngine.installOnboardSignalFix(rootfs: File, onLog: (String) -> Unit = {}) {
    val realCandidates = listOf(
        File(rootfs, "usr/sbin/onboard"),
        File(rootfs, "usr/bin/onboard")
    )
    val real = realCandidates.firstOrNull { it.isFile }
    // Always install wrapper so a later pacman -S onboard works without re-setup
    val wrapper = """
        #!/usr/bin/python3
        # PocketLinux / Local Desktop: swallow OSError from signal.set_wakeup_fd
        # (proot fstat on socket fds returns ENOENT via /proc/self/fd).
        import signal as _signal
        _orig = _signal.set_wakeup_fd
        def _safe_swf(fd, **kwargs):
            try:
                return _orig(fd, **kwargs)
            except OSError:
                return -1
        _signal.set_wakeup_fd = _safe_swf
        import runpy, sys, os
        for p in ("/usr/sbin/onboard", "/usr/bin/onboard"):
            if os.path.isfile(p):
                sys.argv[0] = p
                runpy.run_path(p, run_name="__main__")
                raise SystemExit(0)
        sys.stderr.write("onboard not installed\n")
        raise SystemExit(1)
    """.trimIndent() + "\n"
    try {
        val local = File(rootfs, "usr/local/bin/onboard")
        local.parentFile?.mkdirs()
        local.writeText(wrapper)
        local.setExecutable(true, false)
        if (real != null) {
            onLog("✓ Onboard proot fstat/signal wrapper")
        } else {
            onLog("✓ Onboard wrapper staged (activates when package is installed)")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "installOnboardSignalFix: ${e.message}")
    }
}

/**
 * Disable ssh-agent/gpg-agent in xfce4-session; hide power-manager (Local Desktop P0).
 * Applies to /etc/skel and every home under the rootfs.
 */
fun ContainerRestoreEngine.hardenXfceSessionAndAutostart(rootfs: File, onLog: (String) -> Unit = {}) {
    val sessionXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfce4-session" version="1.0">
          <property name="general" type="empty">
            <property name="SaveOnExit" type="bool" value="false"/>
            <property name="PromptOnLogout" type="bool" value="false"/>
            <property name="SessionName" type="string" value="Default"/>
          </property>
          <property name="startup" type="empty">
            <property name="ssh-agent" type="empty">
              <property name="enabled" type="bool" value="false"/>
            </property>
            <property name="gpg-agent" type="empty">
              <property name="enabled" type="bool" value="false"/>
            </property>
          </property>
          <property name="compat" type="empty">
            <property name="LaunchGNOME" type="bool" value="false"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    val xfwm4Xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="use_compositing" type="bool" value="false"/>
            <property name="vblank_mode" type="string" value="off"/>
            <property name="theme" type="string" value="Default-dark"/>
            <property name="button_layout" type="string" value="O|SHMC"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    val hideDesktop = """
        [Desktop Entry]
        Hidden=true
        OnlyShowIn=XFCE;
    """.trimIndent() + "\n"

    fun patchXfBool(file: File, name: String, value: String) {
        if (!file.isFile) return
        try {
            val text = file.readText()
            if (!text.contains("name=\"$name\"")) return
            file.writeText(
                text.replace(
                    Regex("""name="$name" type="bool" value="[^"]*""""),
                    """name="$name" type="bool" value="$value""""
                )
            )
        } catch (_: Exception) {
        }
    }

    fun patchXfStr(file: File, name: String, value: String) {
        if (!file.isFile) return
        try {
            val text = file.readText()
            if (!text.contains("name=\"$name\"")) return
            file.writeText(
                text.replace(
                    Regex("""name="$name" type="string" value="[^"]*""""),
                    """name="$name" type="string" value="$value""""
                )
            )
        } catch (_: Exception) {
        }
    }

    val homes = mutableListOf(File(rootfs, "etc/skel"), File(rootfs, "root"))
    File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.let { homes.addAll(it) }

    for (home in homes) {
        try {
            val xfconf = File(home, ".config/xfce4/xfconf/xfce-perchannel-xml").apply { mkdirs() }
            // Seed once; if user already customized, only force PRoot safety keys.
            val sessionFile = File(xfconf, "xfce4-session.xml")
            if (!sessionFile.isFile) {
                sessionFile.writeText(sessionXml)
            } else {
                patchXfBool(sessionFile, "SaveOnExit", "false")
                patchXfBool(sessionFile, "PromptOnLogout", "false")
            }
            val xfwm4File = File(xfconf, "xfwm4.xml")
            if (!xfwm4File.isFile) {
                xfwm4File.writeText(xfwm4Xml)
            } else {
                patchXfBool(xfwm4File, "use_compositing", "false")
                patchXfStr(xfwm4File, "vblank_mode", "off")
            }
            // Drop restored client lists that OOM-kill Arch sessions (exit 137)
            File(home, ".cache/sessions").deleteRecursively()
            File(home, ".cache/xfce4/sessions").deleteRecursively()
            val autostart = File(home, ".config/autostart").apply { mkdirs() }
            for (svc in listOf(
                "xfce4-power-manager",
                "xscreensaver",
                "light-locker",
                "gnome-keyring-pkcs11",
                "gnome-keyring-secrets",
                "gnome-keyring-ssh",
                "pulseaudio",
                "tumbler",
                "polkit-gnome-authentication-agent-1",
                "xfce-polkit",
                "at-spi-dbus-bus",
                "xfce4-notifyd",
                "firefox",
                "firefox-esr",
                "chromium",
                "chromium-browser"
            )) {
                File(autostart, "$svc.desktop").writeText(hideDesktop)
            }
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "hardenXfce for ${home.name}: ${e.message}")
        }
    }
    // System-wide XDG autostart overrides (some packages install here)
    try {
        val sysAuto = File(rootfs, "etc/xdg/autostart").apply { mkdirs() }
        for (svc in listOf(
            "xfce4-power-manager",
            "tumbler",
            "pulseaudio",
            "polkit-gnome-authentication-agent-1",
            "xfce-polkit",
            "at-spi-dbus-bus"
        )) {
            File(sysAuto, "$svc.desktop").writeText(
                "[Desktop Entry]\nHidden=true\n"
            )
        }
    } catch (_: Exception) {
    }
    onLog("✓ XFCE: no session restore, compositing off, heavy autostart hidden")
}

/**
 * Delete XFCE saved-client lists every GUI start. startxfce4 restores these and
 * re-launches Firefox → OOM → exit 137 under PRoot on Arch.
 */
fun ContainerRestoreEngine.wipeXfceSavedSessions(rootfs: File, onLog: (String) -> Unit = {}) {
    var wiped = 0
    val homes = mutableListOf(File(rootfs, "etc/skel"), File(rootfs, "root"))
    File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.let { homes.addAll(it) }
    for (home in homes) {
        for (rel in listOf(".cache/sessions", ".cache/xfce4/sessions", ".config/xfce4/sessions")) {
            val dir = File(home, rel)
            if (dir.isDirectory) {
                try {
                    dir.listFiles()?.forEach { f ->
                        if (f.delete() || f.deleteRecursively()) wiped++
                    }
                    // keep empty dir so xfce doesn't recreate mid-race oddly
                } catch (_: Exception) {
                }
            }
        }
    }
    if (wiped > 0) onLog("✓ Cleared $wiped XFCE saved-session file(s) (OOM guard)")
}

/**
 * Remove broken capital-T Thunar wrappers Arch ends up with under PRoot.
 */
fun ContainerRestoreEngine.repairArchThunarWrappers(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        for (orphan in listOf(
            "usr/sbin/Thunar", "usr/sbin/thunar",
            "usr/local/bin/Thunar", "usr/local/bin/thunar"
        )) {
            val f = File(rootfs, orphan)
            if (!f.isFile) continue
            try {
                val t = f.readText()
                if (t.contains(".real") || t.startsWith("#!")) {
                    f.delete()
                    onLog("✓ Removed broken Thunar wrapper: /$orphan")
                }
            } catch (_: Exception) {
            }
        }
        // Ensure wrappers exist for real binaries
        installFileManagerUidWrappers(rootfs)
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "repairArchThunarWrappers: ${e.message}")
    }
}

/** Prevent tumblerd from being spawned via D-Bus thumbnailer service. */
fun ContainerRestoreEngine.maskTumbler(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        // Empty/disabled thumbnailer config
        val thumbDir = File(rootfs, "usr/share/thumbnailers").apply { mkdirs() }
        // Don't delete package files; add a high-priority override that fails closed
        val confDir = File(rootfs, "etc/xdg").apply { mkdirs() }
        File(confDir, "tumbler.rc").writeText(
            """
            [Tumbler]
            Disabled=true
            """.trimIndent() + "\n"
        )
        // Stub tumblerd if binary exists — return immediately.
        // Critical for xfdesktop-settings: it D-Bus-activates tumblerd on the UI
        // thread (GetSupported/GetFlavors). A hanging tumblerd = 10–15s freeze.
        val tumblerBins = listOf(
            File(rootfs, "usr/lib/tumbler-1/tumblerd"),
            File(rootfs, "usr/libexec/tumblerd"),
            File(rootfs, "usr/bin/tumblerd")
        )
        for (b in tumblerBins) {
            if (!b.exists()) continue
            val real = File(b.parentFile, b.name + ".real")
            if (!real.exists()) {
                try {
                    b.renameTo(real)
                } catch (_: Exception) {
                }
            }
            b.writeText("#!/bin/sh\n# PocketLinux: tumblerd disabled under PRoot (freezes Thunar / xfdesktop-settings)\nexit 0\n")
            b.setExecutable(true, false)
        }
        // /usr/local/bin/tumblerd early in PATH
        val local = File(rootfs, "usr/local/bin/tumblerd")
        local.parentFile?.mkdirs()
        local.writeText("#!/bin/sh\nexit 0\n")
        local.setExecutable(true, false)
        onLog("✓ Tumbler/thumbnailer masked (Thunar / Desktop Settings freeze prevention)")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "maskTumbler: ${e.message}")
    }
}

/**
 * Replace bubblewrap with a no-sandbox shim.
 *
 * Modern Arch GTK/gdk-pixbuf use **glycin** loaders that sandbox via `bwrap`
 * (CLONE_NEWUSER). Android SELinux + PRoot cannot provide user namespaces, so
 * glycin-svg fails and GTK/libwnck hit fatal assertions when loading icons —
 * xfce4-session dies and the user only sees a black root window + cursor.
 *
 * Same approach as Local Desktop `setup_fake_bwrap`: strip bwrap flags and exec.
 */
fun ContainerRestoreEngine.installFakeBwrap(rootfs: File, onLog: (String) -> Unit = {}) {
    val script = """
        #!/bin/sh
        # PocketLinux bwrap shim: namespaces unavailable under Android/PRoot.
        # Strip sandbox/bind flags then exec the real command (glycin-svg, etc.).
        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                --ro-bind|--bind|--dev-bind|--bind-try|--ro-bind-try|--dev-bind-try|\
                --file|--bind-data|--ro-bind-data|--symlink|\
                --setenv|--chmod) shift 3 ;;
                --tmpfs|--proc|--dir|\
                --unsetenv|--perms|--cap-add|--cap-drop|\
                --seccomp|--add-seccomp-fd|--info-fd|--json-status-fd|\
                --block-fd|--userns-block-fd|--userns|--userns2|\
                --pidns|--chdir|--dev|--mqueue|--uid|--gid|--hostname) shift 2 ;;
                --unshare-all|--unshare-user|--unshare-user-try|--unshare-pid|\
                --unshare-ipc|--unshare-net|--unshare-uts|--unshare-cgroup|\
                --unshare-cgroup-try|--share-net|--remount-ro|\
                --as-pid-1|--die-with-parent|--new-session|--clearenv|\
                --disable-userns|--assert-userns-disabled) shift ;;
                --) shift; break ;;
                -*) shift ;;
                *) break ;;
            esac
        done
        if [ ${'$'}# -eq 0 ]; then
            echo "bwrap-shim: no command" >&2
            exit 1
        fi
        exec "${'$'}@"
    """.trimIndent() + "\n"

    try {
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val local = File(localBin, "bwrap")
        local.writeText(script)
        local.setExecutable(true, false)

        // Prefer our shim over /usr/bin/bwrap (PATH usually has /usr/local/bin first).
        // Also replace /usr/bin/bwrap if present so absolute shebangs work.
        val usrBin = File(rootfs, "usr/bin/bwrap")
        val usrBinReal = File(rootfs, "usr/bin/bwrap.real")
        try {
            if (usrBin.exists() && !usrBinReal.exists()) {
                // Only rename real bubblewrap once
                val text = try { usrBin.readText().take(80) } catch (_: Exception) { "" }
                if (!text.contains("PocketLinux bwrap shim")) {
                    usrBin.renameTo(usrBinReal)
                }
            }
            usrBin.writeText(script)
            usrBin.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "Could not replace /usr/bin/bwrap: ${e.message}")
        }
        onLog("✓ bwrap shim installed (glycin/SVG icons work without user namespaces)")
    } catch (e: Exception) {
        Log.e(ContainerRestoreEngine.TAG, "installFakeBwrap: ${e.message}", e)
        onLog("⚠ Failed to install bwrap shim: ${e.message}")
    }
}

/**
 * /usr/local/bin wrappers so PATH hits these before /usr/bin.
 * Preload uid spoof only for file managers (never for apt/dpkg).
 */
