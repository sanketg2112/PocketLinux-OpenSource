package com.sg.linuxgo.bootstrap

/** First half of pocketlinux-launch shell (identity, env, DE prep). */
internal fun pocketLinuxLaunchScriptPart1(
    username: String,
    homeDir: String,
    selectedGuiMode: String,
    selectedDE: String,
    startCmd: String,
    gpuEnv: String
): String = """
    #!/bin/sh
    
    # Keep default wallpapers to allow setting standard/custom backgrounds
    
    # Write XFWM4 wrapper to force software rendering for compositing (prevents GPU white/black screen crashes under PRoot)
    if [ "${'$'}(id -u)" = "0" ] && [ ! -f /usr/local/bin/xfwm4 ]; then
        cat > /usr/local/bin/xfwm4 << 'XFWMOF'
#!/bin/sh
# Auto-added by PocketLinux to fix XFWM4 compositing crashes in PRoot with hardware acceleration
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=swrast
export MESA_DEBUG=silent
if [ -x /usr/bin/xfwm4 ]; then
    exec /usr/bin/xfwm4 "${'$'}@"
else
    echo "xfwm4 not found"
    exit 1
fi
XFWMOF
        chmod +x /usr/local/bin/xfwm4
    fi

    # Firefox wrapper + autoconfig: software GL, no HW decode, low process count
    # (video under proot was OOM-killing the whole session with exit 137)
    # Sandbox off under PRoot; Arch/new Firefox: no-op SandboxUtils infobar via AutoConfig JS.
    if [ "${'$'}(id -u)" = "0" ] && { [ -x /usr/bin/firefox ] || [ -x /usr/lib/firefox/firefox ]; }; then
        cat > /usr/local/bin/firefox << 'FFEOF'
#!/bin/sh
# PocketLinux firefox hard-wrap v7
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_FAKE_NO_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_GL_ALWAYS_SOFTWARE=1
export MOZ_WEBRENDER=0
export MOZ_ACCELERATED=0
export MOZ_ENABLE_WAYLAND=0
export MOZ_WEBGL_FORCE_OPENGL=1
export GDK_BACKEND=x11
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=swrast
export MESA_DEBUG=silent
if [ -z "${'$'}PULSE_SERVER" ]; then
    export PULSE_SERVER=tcp:127.0.0.1:14713
fi
if [ -x /usr/lib/firefox/firefox ]; then
    exec /usr/lib/firefox/firefox "${'$'}@"
elif [ -x /usr/lib/firefox-esr/firefox ]; then
    exec /usr/lib/firefox-esr/firefox "${'$'}@"
elif [ -x /usr/bin/firefox ]; then
    exec /usr/bin/firefox "${'$'}@"
else
    echo "firefox not found" >&2
    exit 1
fi
FFEOF
        chmod +x /usr/local/bin/firefox
        for _ff in /usr/lib/firefox /usr/lib/firefox-esr; do
            [ -d "${'$'}_ff" ] || continue
            mkdir -p "${'$'}_ff/defaults/pref" 2>/dev/null || true
            cat > "${'$'}_ff/defaults/pref/autoconfig.js" << 'AJS'
pref("general.config.filename", "pocketlinux.cfg");
pref("general.config.obscure_value", 0);
pref("general.config.sandbox_enabled", false);
AJS
            cat > "${'$'}_ff/pocketlinux.cfg" << 'ACFG'
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
(function () {
  function patch() {
    try {
      var mod = ChromeUtils.importESModule("resource://gre/modules/SandboxUtils.sys.mjs");
      if (mod && mod.SandboxUtils) {
        mod.SandboxUtils.maybeWarnAboutDisabledContentSandbox = function () {};
        mod.SandboxUtils.maybeWarnAboutMissingUserNamespaces = function () {};
        mod.SandboxUtils._sandboxDisabledThisSession = false;
      }
    } catch (e1) {}
  }
  patch();
  try {
    var Services = ChromeUtils.importESModule("resource://gre/modules/Services.sys.mjs").Services;
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
    ["final-ui-startup", "browser-delayed-startup-finished", "sessionstore-windows-restored"].forEach(function (topic) {
      try { Services.obs.addObserver({ observe: function () { stripAndPatch(); } }, topic, false); } catch (e5) {}
    });
  } catch (e6) {}
})();
ACFG
        done
    fi

    # Ensure common Android storage groups exist in guest etc/group and add the guest user to them
    if [ "${'$'}(id -u)" = "0" ] && [ "$username" != "root" ]; then
        for g in "sdcard_rw:1015" "media_rw:1023" "everybody:9997"; do
            gname=${'$'}(echo "${'$'}g" | cut -d: -f1)
            ggid=${'$'}(echo "${'$'}g" | cut -d: -f2)
            if ! grep -q "^${'$'}gname:" /etc/group 2>/dev/null && ! grep -q ":${'$'}ggid:" /etc/group 2>/dev/null; then
                echo "${'$'}gname:x:${'$'}ggid:" >> /etc/group 2>/dev/null || true
            fi
        done

        if command -v usermod >/dev/null 2>&1; then
            for g in root sdcard_rw media_rw everybody; do
                usermod -aG "${'$'}g" "$username" 2>/dev/null || true
            done
        elif command -v addgroup >/dev/null 2>&1; then
            for g in root sdcard_rw media_rw everybody; do
                addgroup "$username" "${'$'}g" 2>/dev/null || true
            done
        fi
    fi

    # Replace permission emblems with transparent SVGs to hide fake permission warning overlays on /sdcard
    if [ "${'$'}(id -u)" = "0" ]; then
        for theme_dir in /usr/share/icons $homeDir/.icons $homeDir/.local/share/icons; do
            if [ -d "${'$'}theme_dir" ]; then
                for theme_path in "${'$'}theme_dir"/*; do
                    if [ -d "${'$'}theme_path" ] && [ ! -f "${'$'}theme_path/.emblems_patched" ]; then
                        find "${'$'}theme_path" -type f \( -name "*emblem-readonly*" -o -name "*emblem-unreadable*" -o -name "*emblem-locked*" \) 2>/dev/null | while read -r file; do
                            if echo "${'$'}file" | grep -q "\.svg$"; then
                                echo '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"/>' > "${'$'}file" 2>/dev/null || true
                            elif echo "${'$'}file" | grep -q "\.png$"; then
                                rm -f "${'$'}file" 2>/dev/null || true
                            fi
                        done
                        touch "${'$'}theme_path/.emblems_patched" 2>/dev/null || true
                    fi
                done
            fi
        done
    fi

    # Do NOT su to the container user. Android NO_NEW_PRIVS blocks setuid
    # sudo/su elevation, so a real user session cannot run apt/dpkg.
    # Stay under proot -0 root capabilities; present as the container user
    # via USER/HOME/LOGNAME (same model as the Android Terminal tab).
    #
    # Never use `su` here — older golden images did `exec su USER -c "$0"`
    # which either fails under Android or leaves a broken root session.

    # Prefer persisted identity if launch script is stale after restore.
    # Order: env from Android → /etc/pocketlinux/username → first /home/* → baked default.
    # Never silently stay as root when a real guest home exists (GUI was logging into root).
    if [ -z "${'$'}POCKETLINUX_USERNAME" ] || [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
        if [ -r /etc/pocketlinux/username ]; then
            __pl_saved=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
            if [ -n "${'$'}__pl_saved" ] && [ "${'$'}__pl_saved" != "root" ]; then
                export POCKETLINUX_USERNAME="${'$'}__pl_saved"
            fi
            unset __pl_saved
        fi
    fi
    if [ -z "${'$'}POCKETLINUX_USERNAME" ] || [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
        for __pl_d in /home/PocketLinux /home/*; do
            [ -d "${'$'}__pl_d" ] || continue
            __pl_b=${'$'}(basename "${'$'}__pl_d")
            case "${'$'}__pl_b" in .*) continue ;; esac
            export POCKETLINUX_USERNAME="${'$'}__pl_b"
            break
        done
        unset __pl_d __pl_b
    fi
    export POCKETLINUX_USERNAME="${'$'}{POCKETLINUX_USERNAME:-$username}"
    if [ -z "${'$'}POCKETLINUX_USERNAME" ] || [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
        if [ "$username" != "root" ] && [ -n "$username" ]; then
            export POCKETLINUX_USERNAME="$username"
        fi
    fi
    if [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
        __pl_home="/root"
    else
        __pl_home="/home/${'$'}POCKETLINUX_USERNAME"
        mkdir -p "${'$'}__pl_home" 2>/dev/null || true
    fi

    # Ensure getpwuid(0) resolves to the guest user (Qt/LXQt ignore USER env if empty
    # and call getpwuid; without a uid0 alias they always become "root").
    if [ "${'$'}POCKETLINUX_USERNAME" != "root" ] && [ -f /etc/passwd ]; then
        __pl_u="${'$'}POCKETLINUX_USERNAME"
        __pl_first=${'$'}(getent passwd 0 2>/dev/null | head -1)
        case "${'$'}__pl_first" in
            "${'$'}__pl_u:"*) ;;
            *)
                __pl_marker="# pocketlinux-uid0-alias"
                __pl_alias="${'$'}__pl_u:x:0:0:${'$'}__pl_u:${'$'}__pl_home:/bin/bash"
                __pl_tmp=${'$'}(mktemp /tmp/passwd.XXXXXX 2>/dev/null || echo /tmp/passwd.pl.$$)
                {
                    echo "${'$'}__pl_marker"
                    echo "${'$'}__pl_alias"
                    # Drop prior managed alias blocks + duplicate uid0 lines for this user
                    awk -v u="${'$'}__pl_u" -v m="${'$'}__pl_marker" '
                        ${'$'}0 == m { skip=1; next }
                        skip { skip=0; split(${'$'}0,a,":"); if (a[1]==u && a[3]=="0") next; }
                        { split(${'$'}0,a,":"); if (a[1]==u && a[3]=="0") next; print }
                    ' /etc/passwd
                } > "${'$'}__pl_tmp" 2>/dev/null && cat "${'$'}__pl_tmp" > /etc/passwd 2>/dev/null
                rm -f "${'$'}__pl_tmp" 2>/dev/null
                echo "✓ passwd uid0 alias refreshed for ${'$'}__pl_u"
                ;;
        esac
        unset __pl_u __pl_first __pl_marker __pl_alias __pl_tmp
    fi
    # Prefer files-only NSS so systemd nss cannot override uid0 → root and hosts does not query systemd-resolved
    if [ -f /etc/nsswitch.conf ] && grep -qE '^passwd:' /etc/nsswitch.conf 2>/dev/null; then
        sed -i 's/^passwd:.*/passwd:         files/' /etc/nsswitch.conf 2>/dev/null || true
        sed -i 's/^group:.*/group:          files/' /etc/nsswitch.conf 2>/dev/null || true
        sed -i 's/^hosts:.*/hosts:          files dns/' /etc/nsswitch.conf 2>/dev/null || true
    fi

    # Ensure sudo shim is installed (replaces real sudo package binary if overwritten)
    if [ -f /etc/passwd ]; then
        __pl_need_sudo=0
        if [ ! -f /usr/bin/sudo ] || ! grep -q "NO_NEW_PRIVS" /usr/bin/sudo 2>/dev/null; then
            __pl_need_sudo=1
        fi
        if [ ! -f /usr/local/bin/sudo ] || ! grep -q "NO_NEW_PRIVS" /usr/local/bin/sudo 2>/dev/null; then
            __pl_need_sudo=1
        fi
        if [ ${'$'}__pl_need_sudo -eq 1 ]; then
            mkdir -p /usr/local/bin 2>/dev/null
            cat > /usr/local/bin/sudo << 'SUDOEOF'
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
SUDOEOF
            chmod 755 /usr/local/bin/sudo 2>/dev/null
            if [ -f /usr/bin/sudo ] && [ ! -f /usr/bin/sudo.real ] && ! grep -q "NO_NEW_PRIVS" /usr/bin/sudo 2>/dev/null; then
                mv /usr/bin/sudo /usr/bin/sudo.real 2>/dev/null || true
            fi
            cp /usr/local/bin/sudo /usr/bin/sudo 2>/dev/null || true
            chmod 755 /usr/bin/sudo 2>/dev/null
            echo "✓ sudo shim restored"
        fi
        unset __pl_need_sudo
    fi
    
    DISPLAY_NUM=1
    export DISPLAY=:${'$'}DISPLAY_NUM
    export USER="${'$'}POCKETLINUX_USERNAME" LOGNAME="${'$'}POCKETLINUX_USERNAME"
    export HOME="${'$'}__pl_home" LANG=C.UTF-8 PWD="${'$'}__pl_home"
    cd "${'$'}HOME" 2>/dev/null || cd /tmp || true
    # Log identity for debugging (getpwuid(0) should match USER via passwd alias)
    echo "PocketLinux session: USER=${'$'}USER HOME=${'$'}HOME id=$(id -un 2>/dev/null || echo '?') euid=$(id -u 2>/dev/null || echo '?') getent0=$(getent passwd 0 2>/dev/null | head -1)"
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export DEBIAN_FRONTEND=noninteractive
    export DEBCONF_NONINTERACTIVE_SEEN=true
    # XDG dirs must point at the guest home — otherwise XFCE falls back to /root.
    export XDG_CONFIG_HOME="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
    export XDG_DATA_HOME="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}"
    export XDG_CACHE_HOME="${'$'}{XDG_CACHE_HOME:-${'$'}HOME/.cache}"
    export XDG_STATE_HOME="${'$'}{XDG_STATE_HOME:-${'$'}HOME/.local/state}"
    mkdir -p "${'$'}XDG_CONFIG_HOME" "${'$'}XDG_DATA_HOME" "${'$'}XDG_CACHE_HOME" "${'$'}XDG_STATE_HOME" 2>/dev/null || true
    # Do NOT export LD_PRELOAD globally — a bad/mismatched link_shim.so
    # breaks every binary (mkdir/sleep/env) with "undefined symbol: __errno".
    # dpkg/apt use wrappers that preload only for package ops.
    unset LD_PRELOAD 2>/dev/null || true
    GUI_MODE=$selectedGuiMode
    
    # Essential directories for GNOME/D-Bus
    # Prefer real uid (proot -i sets this to 1000 for PocketLinux) so
    # /run/user/0 is never used for a non-root desktop session.
    __pl_uid=${'$'}(id -u 2>/dev/null || echo 0)
    if [ "${'$'}GUI_MODE" = "wayland" ]; then
        export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp}"
        export GSETTINGS_BACKEND=keyfile
    else
        if [ "${'$'}__pl_uid" != "0" ]; then
            export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}__pl_uid}"
        else
            export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-${'$'}POCKETLINUX_USERNAME}"
        fi
        mkdir -p ${'$'}XDG_RUNTIME_DIR 2>/dev/null || true
        chmod 700 ${'$'}XDG_RUNTIME_DIR 2>/dev/null || true
    fi
    unset __pl_uid

    # Rendering and IPC fixes for PRoot/Android
    export MOZ_DISABLE_CONTENT_SANDBOX=1
    export MOZ_FAKE_NO_SANDBOX=1
    # Force Firefox to use software WebGL with MOZ_WEBGL_FORCE_OPENGL=1
    export MOZ_GL_ALWAYS_SOFTWARE=1
    export MOZ_WEBGL_FORCE_OPENGL=1
    export CHROME_DEVEL_SANDBOX=
    export NO_SANDBOX=1
    export XDG_SESSION_TYPE=x11
    # Set session type based on GUI mode
    if [ "${'$'}GUI_MODE" = "wayland" ]; then
        export XDG_SESSION_TYPE=wayland
    fi
    # Disable X11 Shared Memory (MIT-SHM) to prevent PRoot/Mesa crashes (BadWindow errors)
    export GDK_DISABLE_XSHM=1
    export QT_X11_NO_MITSHM=1
    export _X11_NO_MITSHM=1
    export GNOME_SHELL_NO_EXPECT_ANY_X11_ACCEL=1
    # KDE/KWin: Use XRender compositing instead of GL (GL fails with KGSL in PRoot)
    # KWIN_COMPOSE=N disables ALL compositing → black screen. XRender works.
    export KWIN_COMPOSE=X
    export QT_XCB_GL_INTEGRATION=none
    # Qt/LXQt: force xcb only for X11 sessions (Wayland uses its own platform plugin)
    if [ "${'$'}GUI_MODE" = "x11" ]; then
        export QT_QPA_PLATFORM=xcb
    fi
    # Suppress KDE crash handler (it can't talk to drkonqi in PRoot)
    export KDE_DEBUG=1
    
    # Suppress AT-SPI accessibility bridge (no a11y bus in PRoot)
    export NO_AT_BRIDGE=1
    export GTK_A11Y=none
    
    # Cursor theme: ensure Adwaita cursors are used instead of default X11 cross cursor
    export XCURSOR_THEME=Adwaita
    export XCURSOR_SIZE=24
    # Prefer classic gdk-pixbuf/rsvg over glycin (glycin needs bwrap namespaces).
    # Our /usr/local/bin/bwrap shim covers absolute paths; this reduces glycin use.
    export GLYCIN_DISABLE=1
    export GSK_RENDERER=cairo
    # GTK/GDK: disable GL globally — prevents black windows + GPU probe RAM spikes
    # under Lorie/PRoot (Arch XFCE was dying with exit 137 shortly after paint).
    export GDK_GL=disable
    export GDK_DEBUG=nogl
    export GSK_RENDERER=cairo
    # Reduce D-Bus service activation timeout from 25s to 1s (fast-fail under PRoot)
    export DBUS_ACTIVATION_TIMEOUT=1000
    # File managers: gvfs-udisks volume monitors hang under PRoot (no udev).
    # Keep volume-monitor mitigations always. GIO_USE_VFS=local disables trash://
    # and breaks Caja Trash + some desktop background UIs — skip it for MATE.
    export GIO_USE_VOLUME_MONITOR=unix
    export GVFS_DISABLE_FUSE=1
    export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1
    if [ "$selectedDE" != "mate" ] && ! echo "$startCmd" | grep -q "mate-session"; then
        export GIO_USE_VFS=local
    else
        unset GIO_USE_VFS 2>/dev/null || true
    fi
    # Arch packages often link memfd/shm paths that return ENOSYS under Android.
    # Keep X11 clients off MIT-SHM (already set) and prefer single-process Firefox.
    export MOZ_DISABLE_GMP_SANDBOX=1
    export MOZ_FORCE_DISABLE_E10S=1
    # Create default cursor index.theme as fallback for DEs that don't read env vars
    if [ "${'$'}(id -u)" = "0" ]; then
        mkdir -p /usr/share/icons/default 2>/dev/null
        cat > /usr/share/icons/default/index.theme << 'CURSOREOF'
[Icon Theme]
Inherits=Adwaita
CURSOREOF
        # Ensure bwrap shim is always present (Arch glycin-svg crashes without it)
        if [ ! -f /usr/local/bin/bwrap ] || ! grep -q 'PocketLinux bwrap shim' /usr/local/bin/bwrap 2>/dev/null; then
            mkdir -p /usr/local/bin
            cat > /usr/local/bin/bwrap << 'BWRAPEOF'
#!/bin/sh
# PocketLinux bwrap shim: namespaces unavailable under Android/PRoot.
while [ $# -gt 0 ]; do
    case "$1" in
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
[ $# -eq 0 ] && exit 1
exec "$@"
BWRAPEOF
            chmod 755 /usr/local/bin/bwrap
            if [ -x /usr/bin/bwrap ] && ! grep -q 'PocketLinux bwrap shim' /usr/bin/bwrap 2>/dev/null; then
                cp -a /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
                cp /usr/local/bin/bwrap /usr/bin/bwrap
                chmod 755 /usr/bin/bwrap
            fi
            echo "✓ Installed bwrap shim for glycin/SVG loaders"
        fi

        # Remove glycin loaders that spawn bwrap subprocesses (hang under PRoot)
        find /usr/lib/gdk-pixbuf-2.0 -name '*glycin*' -delete 2>/dev/null
        find /usr/lib64/gdk-pixbuf-2.0 -name '*glycin*' -delete 2>/dev/null
        if command -v gdk-pixbuf-query-loaders >/dev/null 2>&1; then
            gdk-pixbuf-query-loaders --update-cache 2>/dev/null || true
        elif [ -x /usr/lib/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders ]; then
            /usr/lib/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders --update-cache 2>/dev/null || true
        fi

        # Nuke all D-Bus activatable services that hang under PRoot.
        # xfdesktop-settings calls org.freedesktop.thumbnails.Thumbnailer1
        # GetSupported/GetFlavors synchronously on the UI thread — if tumblerd
        # is activatable but broken under PRoot, the dialog freezes ~10–25s.
        # Arch always ships tumbler (xfce4 group); Debian often does not
        # (no-install-recommends) — that is why Desktop Settings freezes
        # on Arch but not Debian.
        for _dir in /usr/share/dbus-1/services /usr/share/dbus-1/system-services; do
            if [ -d "${'$'}_dir" ]; then
                rm -f "${'$'}_dir"/org.freedesktop.ColorManager* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.UPower* 2>/dev/null  
                rm -f "${'$'}_dir"/org.freedesktop.Accounts* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.RealtimeKit* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.Avahi* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.ModemManager* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.GeoClue* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.bolt* 2>/dev/null
                rm -f "${'$'}_dir"/org.gtk.vfs.* 2>/dev/null
                rm -f "${'$'}_dir"/org.xfce.Tumbler* 2>/dev/null
                rm -f "${'$'}_dir"/org.freedesktop.thumbnails.* 2>/dev/null
            fi
        done
        # Stub tumblerd so even a leftover .service cannot block activation.
        # (Must still DELETE service files — Exec=/bin/true still waits for
        # name ownership and freezes xfdesktop-settings.)
        for _tb in /usr/lib/tumbler-1/tumblerd /usr/libexec/tumblerd /usr/bin/tumblerd; do
            if [ -f "${'$'}_tb" ] && ! grep -q 'PocketLinux: tumblerd disabled' "${'$'}_tb" 2>/dev/null; then
                if [ ! -f "${'$'}_tb.real" ]; then
                    mv "${'$'}_tb" "${'$'}_tb.real" 2>/dev/null || true
                fi
                printf '%s\n' '#!/bin/sh' '# PocketLinux: tumblerd disabled under PRoot (freezes xfdesktop-settings)' 'exit 0' > "${'$'}_tb"
                chmod 755 "${'$'}_tb" 2>/dev/null || true
            fi
        done
        mkdir -p /usr/local/bin
        printf '%s\n' '#!/bin/sh' 'exit 0' > /usr/local/bin/tumblerd
        chmod 755 /usr/local/bin/tumblerd 2>/dev/null || true
        # Wrap Desktop Settings so GIO/GVFS never touch udisks under PRoot
        if [ -x /usr/bin/xfdesktop-settings ] && ! grep -q 'PocketLinux xfdesktop-settings wrapper' /usr/bin/xfdesktop-settings 2>/dev/null; then
            if [ ! -x /usr/bin/xfdesktop-settings.real ]; then
                mv /usr/bin/xfdesktop-settings /usr/bin/xfdesktop-settings.real 2>/dev/null || true
            fi
            if [ -x /usr/bin/xfdesktop-settings.real ]; then
                cat > /usr/bin/xfdesktop-settings << 'XDSEOF'
#!/bin/sh
# PocketLinux xfdesktop-settings wrapper
# Avoid gvfs/udisks volume-monitor D-Bus deadlocks under PRoot (GtkFileChooserButton)
export GIO_USE_VOLUME_MONITOR=unix
export GVFS_DISABLE_FUSE=1
export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1
export GIO_USE_VFS=local
export GDK_GL=disable
pkill -x tumblerd 2>/dev/null || true
exec /usr/bin/xfdesktop-settings.real "$@"
XDSEOF
                chmod 755 /usr/bin/xfdesktop-settings
            fi
        fi
    fi
    
    # Hardware Acceleration Settings (baked profile, then host override).
    $gpuEnv
    # Android injects POCKETLINUX_GPU_MODE + Mesa keys via proot env -i. Re-apply so a
    # golden-image Zink bake-in cannot win after the user picks Freedreno (glmark2 was
    # stuck on "zink Vulkan … MESA_TURNIP" despite container settings).
    ${hostGpuModeOverrideScript()}
    # Multiarch first (Debian/Ubuntu lfdevs kgsl_dri.so). Do not clobber LIBGL_DRIVERS_PATH
    # if the GPU profile already set it (Freedreno).
    export LD_LIBRARY_PATH="/usr/lib/aarch64-linux-gnu/dri:/usr/lib/dri${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
    if [ -z "${'$'}{LIBGL_DRIVERS_PATH-}" ]; then
        export LIBGL_DRIVERS_PATH=/usr/lib/aarch64-linux-gnu/dri:/usr/lib/dri:/usr/lib/xorg/modules/dri
    fi
    # Last line of defence: lfdevs libgallium-*-devel.so + kgsl_dri entry.
    if [ "${'$'}{POCKETLINUX_GPU_MODE:-}" = "adreno_freedreno" ]; then
        unset GALLIUM_DRIVER
        unset ZINK_DESCRIPTORS
        unset ZINK_DEBUG
        _pl_ok=0
        if [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] || [ -e /usr/lib/dri/kgsl_dri.so ]; then
            for _g in /usr/lib/aarch64-linux-gnu/libgallium*-devel.so /usr/lib/libgallium*-devel.so; do
                [ -f "${'$'}_g" ] || continue
                _sz=${'$'}(stat -c%s "${'$'}_g" 2>/dev/null || echo 0)
                if [ "${'$'}_sz" -gt 10000000 ] 2>/dev/null; then _pl_ok=1; break; fi
            done
        fi
        if [ "${'$'}_pl_ok" = "1" ]; then
            # Jail first, system second (swrast must remain reachable).
            export LIBGL_DRIVERS_PATH=/usr/local/lib/pocketlinux-dri:/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
            unset LIBGL_ALWAYS_SOFTWARE
        else
            unset LIBGL_DRIVERS_PATH
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=swrast
            export LIBGL_DRIVERS_PATH=/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri
        fi
    fi
    
    # link() shim is isolated to Xvnc to avoid breaking guest binaries
    SHIM_LIB="/usr/lib/link_shim.so"
    
    # Kill stale Xvnc/websockify from previous session
    pkill -9 -f Xvnc 2>/dev/null || true
    pkill -9 -f websockify 2>/dev/null || true
    
    # Create user home if it doesn't exist (safety)
    mkdir -p "${'$'}HOME" 2>/dev/null || mkdir -p $homeDir 2>/dev/null
    cd "${'$'}HOME" 2>/dev/null || cd $homeDir 2>/dev/null || cd /tmp

    # Ensure /tmp and /run are writable and clean
    # Use actual session uid (1000 under proot -i), never force /run/user/0
    mkdir -p /tmp/.X11-unix /tmp/.ICE-unix "/run/user/${'$'}(id -u)" 2>/dev/null
    chmod 1777 /tmp /tmp/.X11-unix /tmp/.ICE-unix 2>/dev/null || true
    
    # Clean stale session files from previous runs
    rm -f /tmp/.xfsm-ICE-* /tmp/.l2s..* 2>/dev/null
    rm -f /tmp/.X*-lock 2>/dev/null
    rm -f /tmp/.ICE-unix/* 2>/dev/null
    
    # ── Create stubs for missing binaries that XFCE tries to run ──
    # start-pulseaudio-x11: PA is already configured by PocketLinux below
    if [ ! -f /usr/bin/start-pulseaudio-x11 ]; then
        cat > /usr/bin/start-pulseaudio-x11 << 'PASTUB'
#!/bin/sh
# Stub: PulseAudio is pre-configured by PocketLinux launch.sh
exit 0
PASTUB
        chmod +x /usr/bin/start-pulseaudio-x11
    fi
    # pm-is-supported: no power management in PRoot
    if [ ! -f /usr/bin/pm-is-supported ]; then
        cat > /usr/bin/pm-is-supported << 'PMSTUB'
#!/bin/sh
exit 1
PMSTUB
        chmod +x /usr/bin/pm-is-supported
    fi
    
    # ── User config policy ──
    # Preserve home customizations across GUI restarts (themes, helpers, wallpapers,
    # terminal prefs, etc.). Write full defaults only when a file is missing.
    # Safety keys required for PRoot (compositing off, SaveOnExit false) are
    # patched in-place without replacing the whole file.
    XFCE_XML=$homeDir/.config/xfce4/xfconf/xfce-perchannel-xml
    mkdir -p ${'$'}XFCE_XML $homeDir/.config/autostart 2>/dev/null

    # xfconf property patchers (keep user theme/layout; only flip safety keys)
    __pl_xf_bool() {
        __pl_f="${'$'}1"; __pl_n="${'$'}2"; __pl_v="${'$'}3"
        [ -f "${'$'}__pl_f" ] || return 0
        if grep -q "name=\"${'$'}__pl_n\"" "${'$'}__pl_f" 2>/dev/null; then
            sed -i "s/name=\"${'$'}__pl_n\" type=\"bool\" value=\"[^\"]*\"/name=\"${'$'}__pl_n\" type=\"bool\" value=\"${'$'}__pl_v\"/g" "${'$'}__pl_f" 2>/dev/null || true
        fi
    }
    __pl_xf_str() {
        __pl_f="${'$'}1"; __pl_n="${'$'}2"; __pl_v="${'$'}3"
        [ -f "${'$'}__pl_f" ] || return 0
        if grep -q "name=\"${'$'}__pl_n\"" "${'$'}__pl_f" 2>/dev/null; then
            sed -i "s/name=\"${'$'}__pl_n\" type=\"string\" value=\"[^\"]*\"/name=\"${'$'}__pl_n\" type=\"string\" value=\"${'$'}__pl_v\"/g" "${'$'}__pl_f" 2>/dev/null || true
        fi
    }
    
    # XFCE preferred apps (helpers.rc): defaults only when missing — never
    # overwrite a user-chosen FileManager / TerminalEmulator on each boot.
    mkdir -p $homeDir/.config/xfce4 $homeDir/.config 2>/dev/null
    if [ ! -f $homeDir/.config/xfce4/helpers.rc ]; then
        if [ -f /etc/arch-release ]; then
            printf 'FileManager=pcmanfm\nTerminalEmulator=xfce4-terminal\n' > $homeDir/.config/xfce4/helpers.rc
        else
            echo "TerminalEmulator=xfce4-terminal" > $homeDir/.config/xfce4/helpers.rc
        fi
    else
        if [ -f /etc/arch-release ] && ! grep -q "^FileManager=" $homeDir/.config/xfce4/helpers.rc 2>/dev/null; then
            echo "FileManager=pcmanfm" >> $homeDir/.config/xfce4/helpers.rc
        fi
        if ! grep -q "^TerminalEmulator=" $homeDir/.config/xfce4/helpers.rc 2>/dev/null; then
            echo "TerminalEmulator=xfce4-terminal" >> $homeDir/.config/xfce4/helpers.rc
        fi
    fi

    # ── XFCE GUI terminal (xfce4-terminal) — Debian / Ubuntu / Arch / Alpine ──
    # Colors/fonts live in ~/.config/xfce4/terminal/terminalrc under session HOME.
    # Never rewrite that file if it exists. If the user customized while HOME was
    # /root (or config only exists there), copy once into the guest home so the
    # theme survives GUI restarts under /home/<user>.
    __pl_term_home="${'$'}{HOME:-$homeDir}"
    [ -z "${'$'}__pl_term_home" ] && __pl_term_home="$homeDir"
    __pl_term_cfg="${'$'}__pl_term_home/.config/xfce4/terminal"
    mkdir -p "${'$'}__pl_term_cfg" 2>/dev/null || true
    if [ ! -f "${'$'}__pl_term_cfg/terminalrc" ] && [ -f /root/.config/xfce4/terminal/terminalrc ]; then
        if [ "${'$'}__pl_term_home" != "/root" ]; then
            cp -a /root/.config/xfce4/terminal/. "${'$'}__pl_term_cfg/" 2>/dev/null || true
            echo "✓ Migrated xfce4-terminal prefs from /root → ${'$'}__pl_term_home"
        fi
    fi
    # Do not create or force terminalrc defaults (CommandLoginShell, colors, etc.).
    # Stock xfce4-terminal is non-login interactive → loads ~/.bashrc like a normal
    # desktop; user themes (starship/oh-my) install the usual way into ~/.bashrc.
    unset __pl_term_home __pl_term_cfg
    # Repair Debian/Ubuntu alternatives chain when present (harmless if already OK).
    if [ "${'$'}(id -u)" = "0" ] && { [ -x /usr/bin/xfce4-terminal.wrapper ] || [ -x /usr/bin/xfce4-terminal ]; }; then
        if [ ! -e /usr/bin/x-terminal-emulator ]; then
            TERM_BIN=/usr/bin/xfce4-terminal.wrapper
            [ -x "${'$'}TERM_BIN" ] || TERM_BIN=/usr/bin/xfce4-terminal
            mkdir -p /etc/alternatives 2>/dev/null
            ln -sfn "${'$'}TERM_BIN" /etc/alternatives/x-terminal-emulator 2>/dev/null || true
            ln -sfn /etc/alternatives/x-terminal-emulator /usr/bin/x-terminal-emulator 2>/dev/null || true
        fi
    fi

    # Arch: seed default file manager MIME only when unset — keep user choice.
    if [ -f /etc/arch-release ]; then
        if [ ! -f $homeDir/.config/mimeapps.list ]; then
            cat > $homeDir/.config/mimeapps.list << 'MIMEOF'
[Default Applications]
inode/directory=pcmanfm.desktop
MIMEOF
        elif ! grep -q "^inode/directory=" $homeDir/.config/mimeapps.list 2>/dev/null; then
            if grep -q "\[Default Applications\]" $homeDir/.config/mimeapps.list; then
                sed -i '/\[Default Applications\]/a inode/directory=pcmanfm.desktop' $homeDir/.config/mimeapps.list
            else
                printf '\n[Default Applications]\ninode/directory=pcmanfm.desktop\n' >> $homeDir/.config/mimeapps.list
            fi
        fi
    fi
    
    # xfwm4: default file once; thereafter only force compositing off (PRoot safety).
    # Do not rewrite theme / button_layout / user WM prefs every boot.
    if [ ! -f ${'$'}XFCE_XML/xfwm4.xml ]; then
        cat > ${'$'}XFCE_XML/xfwm4.xml << 'XFWMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
    <property name="vblank_mode" type="string" value="off"/>
    <property name="theme" type="string" value="Default-dark"/>
    <property name="button_layout" type="string" value="O|SHMC"/>
  </property>
</channel>
XFWMEOF
    else
        __pl_xf_bool "${'$'}XFCE_XML/xfwm4.xml" use_compositing false
        __pl_xf_str "${'$'}XFCE_XML/xfwm4.xml" vblank_mode off
    fi
    if [ ! -f ${'$'}XFCE_XML/thunar.xml ]; then
        cat > ${'$'}XFCE_XML/thunar.xml << 'THUNAREOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="thunar" version="1.0">
  <property name="misc-show-thumbnails" type="string" value="THUNAR_THUMBNAIL_MODE_NEVER"/>
  <property name="misc-volume-management" type="bool" value="false"/>
</channel>
THUNAREOF
    fi
    # Session: default once; always keep SaveOnExit=false (restored apps → OOM 137).
    # Do not wipe other session preferences the user may have set.
    if [ ! -f ${'$'}XFCE_XML/xfce4-session.xml ]; then
        cat > ${'$'}XFCE_XML/xfce4-session.xml << 'SESSIONEOF'
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
SESSIONEOF
    else
        __pl_xf_bool "${'$'}XFCE_XML/xfce4-session.xml" SaveOnExit false
        __pl_xf_bool "${'$'}XFCE_XML/xfce4-session.xml" PromptOnLogout false
    fi
    # Drop any previously saved client list (restored apps = OOM on Arch/XFCE)
    rm -rf "${'$'}HOME/.cache/sessions" "${'$'}HOME/.cache/xfce4/sessions" 2>/dev/null || true
    rm -f "${'$'}HOME/.cache/sessions/*" 2>/dev/null || true
    mkdir -p "${'$'}HOME/.cache/sessions" 2>/dev/null || true
    # Hide slow/broken/heavy autostart entries every boot (idempotent)
    mkdir -p $homeDir/.config/autostart 2>/dev/null
    for svc in xfce4-power-manager xscreensaver tumbler light-locker \
        gnome-keyring-pkcs11 gnome-keyring-secrets gnome-keyring-ssh \
        pulseaudio polkit-gnome-authentication-agent-1 xfce-polkit \
        xfce4-notifyd at-spi-dbus-bus \
        firefox firefox-esr chromium chromium-browser google-chrome; do
        printf '%s\n' '[Desktop Entry]' 'Hidden=true' > $homeDir/.config/autostart/${'$'}svc.desktop
    done
    # System-wide XDG autostart overrides (packages drop files here on Arch/Void)
    mkdir -p /etc/xdg/autostart 2>/dev/null || true
    for svc in xfce4-power-manager tumbler pulseaudio \
        polkit-gnome-authentication-agent-1 xfce-polkit at-spi-dbus-bus; do
        printf '%s\n' '[Desktop Entry]' 'Hidden=true' > /etc/xdg/autostart/${'$'}svc.desktop 2>/dev/null || true
    done

    # Ensure /sdcard/ is in GTK bookmarks for Thunar sidebar ONLY if it is readable
    mkdir -p $homeDir/.config/gtk-3.0 2>/dev/null
    if ls /sdcard >/dev/null 2>&1; then
        if [ ! -f $homeDir/.config/gtk-3.0/bookmarks ] || ! grep -q "file:///sdcard" $homeDir/.config/gtk-3.0/bookmarks 2>/dev/null; then
            echo "file:///sdcard/ sdcard" >> $homeDir/.config/gtk-3.0/bookmarks
        fi
    else
        if [ -f $homeDir/.config/gtk-3.0/bookmarks ]; then
            sed -i '\#file:///sdcard#d' $homeDir/.config/gtk-3.0/bookmarks 2>/dev/null || true
        fi
    fi

    # ── KDE Plasma-specific PRoot fixes ──
    # Defaults only when missing; patch compositing safety keys if file exists.
    # Do not rewrite themes / plugins / wallpapers the user already customized.
    if echo "$startCmd" | grep -q "startplasma"; then
        KDE_CONF=$homeDir/.config
        mkdir -p ${'$'}KDE_CONF 2>/dev/null

        if [ ! -f ${'$'}KDE_CONF/kwinrc ]; then
            cat > ${'$'}KDE_CONF/kwinrc << 'KWINEOF'
[Compositing]
Backend=XRender
Enabled=true
GLCore=false
OpenGLIsUnsafe=true
AnimationSpeed=3
HiddenPreviews=5

[Effect-overview]
BorderActivate=9

[Plugins]
blurEnabled=false
contrastEnabled=false
glideEnabled=false
kwin4_effect_fadeEnabled=false
magiclampEnabled=false
slideEnabled=false
KWINEOF
        else
            # Keep user look; force XRender path so KWin does not GL-crash under PRoot.
            if grep -q '^Backend=' ${'$'}KDE_CONF/kwinrc 2>/dev/null; then
                sed -i 's/^Backend=.*/Backend=XRender/' ${'$'}KDE_CONF/kwinrc 2>/dev/null || true
            fi
            if grep -q '^GLCore=' ${'$'}KDE_CONF/kwinrc 2>/dev/null; then
                sed -i 's/^GLCore=.*/GLCore=false/' ${'$'}KDE_CONF/kwinrc 2>/dev/null || true
            fi
            if grep -q '^OpenGLIsUnsafe=' ${'$'}KDE_CONF/kwinrc 2>/dev/null; then
                sed -i 's/^OpenGLIsUnsafe=.*/OpenGLIsUnsafe=true/' ${'$'}KDE_CONF/kwinrc 2>/dev/null || true
            fi
        fi

        if [ ! -f ${'$'}KDE_CONF/kscreenlockerrc ]; then
            cat > ${'$'}KDE_CONF/kscreenlockerrc << 'LOCKEOF'
[Daemon]
Autolock=false
LockGrace=0
LockOnResume=false
Timeout=0
LOCKEOF
        else
            sed -i 's/^Autolock=.*/Autolock=false/' ${'$'}KDE_CONF/kscreenlockerrc 2>/dev/null || true
            sed -i 's/^LockOnResume=.*/LockOnResume=false/' ${'$'}KDE_CONF/kscreenlockerrc 2>/dev/null || true
        fi

        if [ ! -f ${'$'}KDE_CONF/baloofilerc ]; then
            cat > ${'$'}KDE_CONF/baloofilerc << 'BALEOF'
[Basic Settings]
Indexing-Enabled=false
BALEOF
        else
            sed -i 's/^Indexing-Enabled=.*/Indexing-Enabled=false/' ${'$'}KDE_CONF/baloofilerc 2>/dev/null || true
        fi

        # Hide broken-under-PRoot autostart (idempotent; not a user theme)
        mkdir -p ${'$'}KDE_CONF/autostart 2>/dev/null
        cat > ${'$'}KDE_CONF/autostart/kglobalaccel.desktop << 'KGEOF'
[Desktop Entry]
Hidden=true
KGEOF
        cat > ${'$'}KDE_CONF/autostart/org.kde.kdeconnect.daemon.desktop << 'KDCEOF'
[Desktop Entry]
Hidden=true
KDCEOF

        # xsettingsd: seed once only — do not clobber user GTK/Qt theme each boot
        mkdir -p $homeDir/.config/xsettingsd 2>/dev/null
        if [ ! -f $homeDir/.config/xsettingsd/xsettingsd.conf ]; then
            cat > $homeDir/.config/xsettingsd/xsettingsd.conf << 'XSEOF'
Net/ThemeName "Breeze"
Net/IconThemeName "PocketLinux"
Gtk/CursorThemeName "breeze_cursors"
Net/EnableEventSounds 0
Net/EnableInputFeedbackSounds 0
Xft/Antialias 1
Xft/Hinting 1
Xft/HintStyle "hintslight"
Xft/RGBA "rgb"
Xft/DPI 96
Gtk/ButtonImages 1
Gtk/MenuImages 1
Gtk/CursorThemeSize 12
XSEOF
        fi

        if [ "$selectedDE" = "kde" ] && command -v xsettingsd >/dev/null 2>&1; then
            xsettingsd 2>/dev/null &
        fi

        export PLASMA_DEFAULT_WALLPAPER=/usr/share/backgrounds/xfce/xfce-blue.jpg
    fi

    # ── LXQt-specific PRoot fixes (black screen + root identity) ──
    # Always use runtime HOME / POCKETLINUX_USERNAME (never bake-time paths).
    # lxqt-session/Qt call getpwuid and read session Environment — both must
    # present the guest user, not root under proot -0.
    if echo "$startCmd" | grep -q "lxqt\|startlxqt"; then
        export XDG_CURRENT_DESKTOP=LXQt
        export XDG_SESSION_DESKTOP=lxqt
        export XDG_MENU_PREFIX=lxqt-
        export QT_QPA_PLATFORM=xcb
        export QT_XCB_GL_INTEGRATION=none
        export QT_QUICK_BACKEND=software
        export QT_OPENGL=software
        export LIBGL_ALWAYS_SOFTWARE="${'$'}{LIBGL_ALWAYS_SOFTWARE:-1}"

        # Prefer guest home; migrate stale /root LXQt configs if needed
        if [ "${'$'}HOME" = "/root" ] && [ -n "${'$'}POCKETLINUX_USERNAME" ] && [ "${'$'}POCKETLINUX_USERNAME" != "root" ]; then
            export HOME="/home/${'$'}POCKETLINUX_USERNAME"
            export USER="${'$'}POCKETLINUX_USERNAME" LOGNAME="${'$'}POCKETLINUX_USERNAME"
            mkdir -p "${'$'}HOME" 2>/dev/null || true
            cd "${'$'}HOME" 2>/dev/null || true
        fi
        if [ "${'$'}HOME" != "/root" ] && [ -d /root/.config/lxqt ] && [ ! -d "${'$'}HOME/.config/lxqt" ]; then
            mkdir -p "${'$'}HOME/.config" 2>/dev/null || true
            cp -a /root/.config/lxqt "${'$'}HOME/.config/" 2>/dev/null || true
            cp -a /root/.config/pcmanfm-qt "${'$'}HOME/.config/" 2>/dev/null || true
            cp -a /root/.config/openbox "${'$'}HOME/.config/" 2>/dev/null || true
            echo "✓ Migrated LXQt configs from /root to ${'$'}HOME"
        fi

        LXQT_CONF="${'$'}HOME/.config"
        mkdir -p "${'$'}LXQT_CONF/lxqt" "${'$'}LXQT_CONF/pcmanfm-qt/lxqt" "${'$'}LXQT_CONF/openbox" "${'$'}LXQT_CONF/autostart" 2>/dev/null
        export XDG_CONFIG_HOME="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
        export XDG_DATA_HOME="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}"
        export XDG_CACHE_HOME="${'$'}{XDG_CACHE_HOME:-${'$'}HOME/.cache}"
        export XDG_STATE_HOME="${'$'}{XDG_STATE_HOME:-${'$'}HOME/.local/state}"
        mkdir -p "${'$'}XDG_CONFIG_HOME" "${'$'}XDG_DATA_HOME" "${'$'}XDG_CACHE_HOME" "${'$'}XDG_STATE_HOME" 2>/dev/null || true

        # session.conf: seed defaults once; always refresh Environment identity only.
        # Do not wipe [General] window_manager / leave_confirmation user choices.
        __pl_lxqt_env_upsert() {
            __pl_sc="${'$'}1"
            if [ ! -f "${'$'}__pl_sc" ]; then
                cat > "${'$'}__pl_sc" << LXQTEOF
[General]
window_manager=openbox
leave_confirmation=false

[Environment]
USER=${'$'}POCKETLINUX_USERNAME
LOGNAME=${'$'}POCKETLINUX_USERNAME
HOME=${'$'}HOME
POCKETLINUX_USERNAME=${'$'}POCKETLINUX_USERNAME
QT_QPA_PLATFORM=xcb
QT_XCB_GL_INTEGRATION=none
LXQTEOF
                return 0
            fi
            if ! grep -q '^\[Environment\]' "${'$'}__pl_sc" 2>/dev/null; then
                printf '\n[Environment]\n' >> "${'$'}__pl_sc"
            fi
            __pl_env_set() {
                __pl_k="${'$'}1"; __pl_v="${'$'}2"
                if grep -q "^${'$'}__pl_k=" "${'$'}__pl_sc" 2>/dev/null; then
                    sed -i "s|^${'$'}__pl_k=.*|${'$'}__pl_k=${'$'}__pl_v|" "${'$'}__pl_sc" 2>/dev/null || true
                else
                    # Insert after [Environment] header
                    sed -i "/^\[Environment\]/a ${'$'}__pl_k=${'$'}__pl_v" "${'$'}__pl_sc" 2>/dev/null || \
                        echo "${'$'}__pl_k=${'$'}__pl_v" >> "${'$'}__pl_sc"
                fi
            }
            __pl_env_set USER "${'$'}POCKETLINUX_USERNAME"
            __pl_env_set LOGNAME "${'$'}POCKETLINUX_USERNAME"
            __pl_env_set HOME "${'$'}HOME"
            __pl_env_set POCKETLINUX_USERNAME "${'$'}POCKETLINUX_USERNAME"
            __pl_env_set QT_QPA_PLATFORM xcb
            __pl_env_set QT_XCB_GL_INTEGRATION none
        }
        __pl_lxqt_env_upsert "${'$'}LXQT_CONF/lxqt/session.conf"

        if [ ! -f "${'$'}LXQT_CONF/lxqt/lxqt.conf" ]; then
            cat > "${'$'}LXQT_CONF/lxqt/lxqt.conf" << 'LXQTEOF'
[General]
icon_theme=Tela
theme=dark
LXQTEOF
        fi

        if [ ! -f "${'$'}LXQT_CONF/pcmanfm-qt/lxqt/settings.conf" ]; then
            cat > "${'$'}LXQT_CONF/pcmanfm-qt/lxqt/settings.conf" << 'LXQTEOF'
[Desktop]
Wallpaper=/usr/share/backgrounds/xfce/xfce-blue.jpg
WallpaperMode=stretch
FgColor=#ffffff
BgColor=#2d2d2d
ShadowColor=#000000
LXQTEOF
        fi

""".trimIndent()
