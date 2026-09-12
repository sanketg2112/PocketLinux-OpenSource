package com.sg.linuxgo

import android.util.Log
import java.io.File

/**
 * X11 mini-session (no startxfce4 / no session restore).
 *
 * Used on Arch always, and on tawcroot for all distros.
 * Intentional Log Out only via [user_logout] marker.
 *
 * v7: Alpine support — apk.static staged by host; Alpine xfconfd paths; optional
 * openbox install; busybox-safe /proc scans. TCP dbus from parent launch script.
 */
fun ContainerRestoreEngine.ensureArchXfceMiniSession(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        ensureDesktopLogoutHelpers(rootfs) { /* quiet; logged once from prep if needed */ }

        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val bin = File(localBin, "pocketlinux-xfce-session")
        bin.writeText(
            """
            #!/bin/sh
            # PocketLinux X11 mini-session v9 — NO startxfce4.
            # mini_session_v9: hold Android overlay until xfce4-panel + xfdesktop + wm.
            export XDG_CURRENT_DESKTOP="${'$'}{XDG_CURRENT_DESKTOP:-XFCE}"
            export XDG_SESSION_DESKTOP="${'$'}{XDG_SESSION_DESKTOP:-xfce}"
            export XDG_SESSION_TYPE=x11
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export GDK_GL=disable
            export GDK_DEBUG=nogl
            export GSK_RENDERER=cairo
            export QT_X11_NO_MITSHM=1
            export _X11_NO_MITSHM=1
            export GIO_USE_VOLUME_MONITOR=unix
            export GVFS_DISABLE_FUSE=1
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export POCKETLINUX_MINI_SESSION=1

            if [ -z "${'$'}{MESA_LOADER_DRIVER_OVERRIDE-}" ] && [ -z "${'$'}{GALLIUM_DRIVER-}" ]; then
                export LIBGL_ALWAYS_SOFTWARE=1
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=swrast
            fi
            export MESA_DEBUG=silent

            pkill -9 -x polkit-gnome-authentication-agent-1 2>/dev/null || true
            pkill -9 -x xfce-polkit 2>/dev/null || true
            rm -f /tmp/pocketlinux-block-heavy /tmp/pocketlinux-session-ended 2>/dev/null || true
            rm -rf "${'$'}HOME/.cache/sessions" "${'$'}HOME/.cache/xfce4/sessions" 2>/dev/null || true
            mkdir -p "${'$'}HOME/.cache/sessions" \
                     "${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml" 2>/dev/null || true

            # D-Bus from parent launch script
            if [ -z "${'$'}{DBUS_SESSION_BUS_ADDRESS-}" ]; then
                if [ -S /tmp/dbus-session ]; then
                    export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
                elif [ -n "${'$'}{XDG_RUNTIME_DIR-}" ] && [ -S "${'$'}XDG_RUNTIME_DIR/bus" ]; then
                    export DBUS_SESSION_BUS_ADDRESS=unix:path=${'$'}XDG_RUNTIME_DIR/bus
                fi
            fi
            if [ -z "${'$'}{DBUS_SYSTEM_BUS_ADDRESS-}" ] && [ -S /run/dbus/system_bus_socket ]; then
                export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
            fi
            mkdir -p "${'$'}{XDG_RUNTIME_DIR:-/tmp}" 2>/dev/null || true
            chmod 700 "${'$'}{XDG_RUNTIME_DIR:-/tmp}" 2>/dev/null || true

            # pgrep -x is unreliable under tawcroot (comm often shows libtawcroot.so).
            # Match guest cmdline instead. Race-safe: skip vanished /proc entries.
            __pl_has() {
                _pat="${'$'}1"
                for _c in /proc/[0-9]*/cmdline; do
                    [ -e "${'$'}_c" ] || continue
                    [ -r "${'$'}_c" ] || continue
                    _s=${'$'}(tr '\0' ' ' < "${'$'}_c" 2>/dev/null) || continue
                    case "${'$'}_s" in
                        *"${'$'}_pat"*) return 0 ;;
                    esac
                done
                return 1
            }
            __pl_logout() {
                if [ -f /tmp/pocketlinux-session-ended ]; then
                    _mk=${'$'}(cat /tmp/pocketlinux-session-ended 2>/dev/null || true)
                    case "${'$'}_mk" in *user_logout*) return 0 ;; esac
                fi
                return 1
            }

            __PL_IS_ALPINE=0
            if [ -f /etc/alpine-release ] || [ -f /etc/apk/world ]; then
                __PL_IS_ALPINE=1
            fi
            echo "mini-session v9: DISPLAY=${'$'}DISPLAY USER=${'$'}USER alpine=${'$'}__PL_IS_ALPINE dbus=${'$'}{DBUS_SESSION_BUS_ADDRESS:-none} runtime=${'$'}{POCKETLINUX_RUNTIME:-?}"

            # Alpine: ensure a non-xfconf WM fallback exists (xfwm4 needs xfconf).
            if [ "${'$'}__PL_IS_ALPINE" = "1" ] && [ "${'$'}{POCKETLINUX_RUNTIME:-}" = "tawcroot" ]; then
                if ! command -v openbox >/dev/null 2>&1 && ! command -v twm >/dev/null 2>&1; then
                    if [ ! -f /var/lib/pocketlinux/tawc_alpine_openbox_try_v1 ]; then
                        mkdir -p /var/lib/pocketlinux 2>/dev/null || true
                        touch /var/lib/pocketlinux/tawc_alpine_openbox_try_v1 2>/dev/null || true
                        echo "mini-session: Alpine tawcroot — trying apk add openbox (one-shot)"
                        if [ -x /sbin/apk.static ]; then
                            /sbin/apk.static add --allow-untrusted --no-cache openbox 2>/tmp/pl-apk-openbox.log || true
                        elif command -v apk >/dev/null 2>&1; then
                            apk add --no-cache openbox 2>/tmp/pl-apk-openbox.log || true
                        fi
                        if command -v openbox >/dev/null 2>&1; then
                            echo "mini-session: openbox installed"
                        else
                            echo "WARNING: mini-session: openbox not installed (see /tmp/pl-apk-openbox.log)"
                        fi
                    fi
                fi
            fi

            __pl_find_xfconfd() {
                # Debian multiarch first, then Alpine/musl flat paths
                for _p in \
                    /usr/lib/aarch64-linux-gnu/xfce4/xfconf/xfconfd \
                    /usr/lib/x86_64-linux-gnu/xfce4/xfconf/xfconfd \
                    /usr/lib/arm-linux-gnueabihf/xfce4/xfconf/xfconfd \
                    /usr/lib/xfce4/xfconf/xfconfd \
                    /usr/libexec/xfce4/xfconf/xfconfd \
                    /usr/lib64/xfce4/xfconf/xfconfd \
                    /usr/lib/libexec/xfce4/xfconf/xfconfd
                do
                    [ -x "${'$'}_p" ] && { echo "${'$'}_p"; return 0; }
                done
                # Alpine / musl: shallow find (busybox find is fine)
                _f=${'$'}(find /usr/lib /usr/libexec /usr/lib64 -name xfconfd -type f 2>/dev/null | head -1)
                if [ -n "${'$'}_f" ] && [ -x "${'$'}_f" ]; then
                    echo "${'$'}_f"
                    return 0
                fi
                command -v xfconfd 2>/dev/null
            }

            __pl_xfconf_ok() {
                if command -v xfconf-query >/dev/null 2>&1; then
                    xfconf-query -c xfwm4 -l >/dev/null 2>&1 && return 0
                    xfconf-query -c xfce4-desktop -l >/dev/null 2>&1 && return 0
                fi
                if command -v dbus-send >/dev/null 2>&1 && [ -n "${'$'}{DBUS_SESSION_BUS_ADDRESS-}" ]; then
                    dbus-send --session --print-reply --dest=org.xfce.Xfconf \
                        /org/xfce/Xfconf org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1 && return 0
                fi
                return 1
            }

            __pl_start_xfconfd() {
                if __pl_xfconf_ok; then
                    echo "mini-session: xfconf already usable"
                    return 0
                fi
                # kill stale broken instance
                for _c in /proc/[0-9]*/cmdline; do
                    [ -r "${'$'}_c" ] || continue
                    _s=${'$'}(tr '\0' ' ' < "${'$'}_c" 2>/dev/null || true)
                    case "${'$'}_s" in
                        *xfconfd*)
                            _pid=${'$'}(echo "${'$'}_c" | sed -n 's|.*/proc/\([0-9]*\)/cmdline|\1|p')
                            [ -n "${'$'}_pid" ] && kill "${'$'}_pid" 2>/dev/null || true
                            ;;
                    esac
                done
                sleep 0.15

                _xc=${'$'}(__pl_find_xfconfd)
                if [ -n "${'$'}_xc" ]; then
                    echo "mini-session: starting xfconfd: ${'$'}_xc"
                    # No --daemon: double-fork is flaky under some rootless runtimes.
                    "${'$'}_xc" >/tmp/pl-xfconfd.log 2>&1 &
                    echo ${'$'}! > /tmp/pl-xfconfd.pid 2>/dev/null || true
                else
                    echo "WARNING: mini-session: xfconfd binary not found"
                fi

                # dbus activation as second chance
                if command -v dbus-send >/dev/null 2>&1 && [ -n "${'$'}{DBUS_SESSION_BUS_ADDRESS-}" ]; then
                    dbus-send --session --print-reply --dest=org.freedesktop.DBus \
                        /org/freedesktop/DBus org.freedesktop.DBus.StartServiceByName \
                        string:org.xfce.Xfconf uint32:0 >/tmp/pl-xfconf-activate.log 2>&1 || true
                fi

                _i=0
                while [ ${'$'}_i -lt 40 ]; do
                    if __pl_xfconf_ok; then
                        echo "mini-session: xfconf ready (try=${'$'}_i)"
                        return 0
                    fi
                    sleep 0.1
                    _i=${'$'}(( _i + 1 ))
                done
                echo "WARNING: mini-session: xfconf still not usable"
                if [ -f /tmp/pl-xfconfd.log ]; then
                    echo "xfconfd log:"; cat /tmp/pl-xfconfd.log 2>/dev/null | tail -15
                fi
                if [ -f /tmp/pl-xfconf-activate.log ]; then
                    echo "xfconf activate:"; cat /tmp/pl-xfconf-activate.log 2>/dev/null | tail -10
                fi
                return 1
            }

            __pl_start_wm() {
                # xfwm4 first once xfconf works (TCP dbus); else openbox/twm/etc.
                for _wm in \
                    xfwm4 \
                    openbox \
                    matchbox-window-manager \
                    fluxbox \
                    icewm \
                    metacity \
                    marco \
                    twm
                do
                    _bin=""
                    if [ -x "/usr/local/bin/${'$'}_wm" ]; then
                        _bin="/usr/local/bin/${'$'}_wm"
                    elif [ -x "/usr/bin/${'$'}_wm" ]; then
                        _bin="/usr/bin/${'$'}_wm"
                    elif command -v "${'$'}_wm" >/dev/null 2>&1; then
                        _bin=${'$'}(command -v "${'$'}_wm")
                    fi
                    [ -n "${'$'}_bin" ] || continue

                    if [ "${'$'}_wm" = "xfwm4" ] && ! __pl_xfconf_ok; then
                        echo "mini-session: skip xfwm4 (xfconf not ready)"
                        continue
                    fi

                    echo "mini-session: starting WM ${'$'}_bin"
                    "${'$'}_bin" >/tmp/pl-wm.log 2>&1 &
                    sleep 0.55
                    if __pl_has "${'$'}_wm" || __pl_has "${'$'}_bin"; then
                        echo "mini-session: WM up (${'$'}_wm)"
                        # Replace X cross cursor with arrow when possible
                        if command -v xsetroot >/dev/null 2>&1; then
                            xsetroot -cursor_name left_ptr 2>/dev/null || true
                        fi
                        return 0
                    fi
                    echo "WARNING: mini-session: ${'$'}_wm exited early"
                    tail -8 /tmp/pl-wm.log 2>/dev/null || true
                done
                return 1
            }

            # Resolve wallpaper: prefer user's existing choice; never force pocketlinux_wp.
            # user_wp_only: install XML often still has pocketlinux last-image on monitor0
            # while the user changed a later monitor key — skip brand paths if another exists.
            _pl_wp_pick=""
            _pl_wp_brand=""
            __pl_consider_wp() {
                _v=${'$'}(printf '%s' "${'$'}1" | sed "s/^'//;s/'$//")
                [ -n "${'$'}_v" ] && [ -f "${'$'}_v" ] || return 1
                case "${'$'}_v" in
                    *pocketlinux_wp*|*pocketlinux-wallpaper*)
                        [ -z "${'$'}_pl_wp_brand" ] && _pl_wp_brand="${'$'}_v"
                        ;;
                    *)
                        [ -z "${'$'}_pl_wp_pick" ] && _pl_wp_pick="${'$'}_v"
                        ;;
                esac
                return 0
            }
            __pl_user_wp() {
                _pl_wp_pick=""
                _pl_wp_brand=""
                if __pl_xfconf_ok && command -v xfconf-query >/dev/null 2>&1; then
                    for _p in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep last-image || true); do
                        __pl_consider_wp "${'$'}(xfconf-query -c xfce4-desktop -p "${'$'}_p" 2>/dev/null || true)"
                    done
                fi
                _xml="${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
                if [ -f "${'$'}_xml" ]; then
                    _tmp=/tmp/pl-last-image.${'$'}$
                    sed -n 's/.*name="last-image"[^>]*value="\([^"]*\)".*/\1/p' "${'$'}_xml" > "${'$'}_tmp" 2>/dev/null || true
                    while IFS= read -r _v || [ -n "${'$'}_v" ]; do
                        __pl_consider_wp "${'$'}_v"
                    done < "${'$'}_tmp"
                    rm -f "${'$'}_tmp"
                fi
                if [ -n "${'$'}_pl_wp_pick" ]; then
                    printf '%s\n' "${'$'}_pl_wp_pick"
                    return 0
                fi
                if [ -n "${'$'}_pl_wp_brand" ]; then
                    printf '%s\n' "${'$'}_pl_wp_brand"
                    return 0
                fi
                return 1
            }

            __pl_set_wp() {
                # Interim root paint only — do NOT write xfconf (preserves user backdrop).
                # No brand-file fallback: a missing last-image is solid, not pocketlinux_wp.
                _wp=${'$'}(__pl_user_wp || true)
                [ -n "${'$'}_wp" ] || return 1
                if command -v feh >/dev/null 2>&1; then
                    feh --no-fehbg --bg-fill "${'$'}_wp" 2>/dev/null || true
                elif command -v xwallpaper >/dev/null 2>&1; then
                    xwallpaper --zoom "${'$'}_wp" 2>/dev/null || true
                fi
                echo "mini-session: interim wallpaper ${'$'}_wp (user prefs not overwritten)"
                return 0
            }

            __pl_seed_panel_config() {
                _pdir="${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
                mkdir -p "${'$'}_pdir" "${'$'}HOME/.config/xfce4/panel" 2>/dev/null || true
                _pxml="${'$'}_pdir/xfce4-panel.xml"
                if [ -f "${'$'}_pxml" ] && [ -s "${'$'}_pxml" ]; then
                    return 0
                fi
                # Prefer distro defaults so first panel start has plugins
                for _src in \
                    /etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml \
                    /etc/xdg/xfce4/panel/default.xml
                do
                    if [ -f "${'$'}_src" ]; then
                        if echo "${'$'}_src" | grep -q 'default.xml'; then
                            # default.xml is panel layout, not always xfconf channel
                            cp -f "${'$'}_src" "${'$'}HOME/.config/xfce4/panel/default.xml" 2>/dev/null || true
                        else
                            cp -f "${'$'}_src" "${'$'}_pxml" 2>/dev/null || true
                        fi
                        echo "mini-session: seeded panel config from ${'$'}_src"
                        return 0
                    fi
                done
                # Minimal channel so panel can migrate defaults
                cat > "${'$'}_pxml" << 'PANXML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
</channel>
PANXML
                echo "mini-session: wrote minimal xfce4-panel.xml (will migrate defaults)"
                return 0
            }

            __pl_start_panel_once() {
                command -v xfce4-panel >/dev/null 2>&1 || return 1
                __pl_xfconf_ok || return 1
                __pl_seed_panel_config
                # Prefer disable-wm-check so panel does not wait on session manager
                if xfce4-panel --help 2>&1 | grep -q disable-wm-check; then
                    xfce4-panel --disable-wm-check >/tmp/pl-panel.log 2>&1 &
                else
                    xfce4-panel >/tmp/pl-panel.log 2>&1 &
                fi
                sleep 0.6
                if __pl_has xfce4-panel; then
                    echo "mini-session: xfce4-panel up"
                    return 0
                fi
                echo "WARNING: mini-session: xfce4-panel exited early"
                tail -20 /tmp/pl-panel.log 2>/dev/null || true
                return 1
            }

            __pl_start_panel() {
                if command -v xfsettingsd >/dev/null 2>&1 && __pl_xfconf_ok; then
                    xfsettingsd --replace >/tmp/pl-xfsettingsd.log 2>&1 &
                    sleep 0.25
                fi

                # Interim paint from user (or brand fallback) before xfdesktop
                __pl_set_wp

                if __pl_xfconf_ok; then
                    _try=0
                    while [ ${'$'}_try -lt 4 ]; do
                        __pl_start_panel_once && break
                        _try=${'$'}(( _try + 1 ))
                        echo "mini-session: panel retry ${'$'}_try"
                        sleep 0.5
                    done
                else
                    echo "mini-session: skip xfce4-panel until xfconf ready"
                fi

                if command -v xfdesktop >/dev/null 2>&1; then
                    # Do not rewrite last-image — xfdesktop uses user's xfconf
                    xfdesktop >/tmp/pl-xfdesktop.log 2>&1 &
                    sleep 0.35
                    if ! __pl_has xfdesktop; then
                        echo "WARNING: mini-session: xfdesktop exited early"
                        tail -12 /tmp/pl-xfdesktop.log 2>/dev/null || true
                    fi
                fi
                if command -v xsetroot >/dev/null 2>&1; then
                    xsetroot -cursor_name left_ptr 2>/dev/null || true
                fi
            }

            __pl_desktop_alive() {
                __pl_has xfwm4 || __pl_has openbox || __pl_has matchbox \
                    || __pl_has fluxbox || __pl_has icewm || __pl_has metacity \
                    || __pl_has marco || __pl_has twm \
                    || __pl_has xfce4-panel || __pl_has xfdesktop \
                    || __pl_has xfsettingsd
            }

            __pl_xfce_stack_up() {
                (__pl_has xfwm4 || __pl_has openbox) || return 1
                __pl_has xfce4-panel || return 1
                __pl_has xfdesktop || return 1
                return 0
            }

            __pl_signal_xfce_ready() {
                _w=0
                while [ ${'$'}_w -lt 48 ]; do
                    __pl_logout && return 1
                    if __pl_xfce_stack_up; then
                        sleep 1.8
                        echo xfce > /tmp/pocketlinux-desktop-ready 2>/dev/null || true
                        echo "mini-session v9: XFCE stack ready (wm+panel+xfdesktop)"
                        return 0
                    fi
                    sleep 0.25
                    _w=${'$'}(( _w + 1 ))
                done
                echo panel > /tmp/pocketlinux-desktop-ready 2>/dev/null || true
                echo "mini-session v9: overlay fallback (panel marker)"
                return 0
            }

            # ── Main restart loop: only user_logout ends the session ─────────
            _round=0
            while true; do
                if __pl_logout; then
                    echo "mini-session v9: intentional user_logout"
                    break
                fi
                _round=${'$'}(( _round + 1 ))
                echo "mini-session v9: start round ${'$'}_round alpine=${'$'}__PL_IS_ALPINE dbus=${'$'}{DBUS_SESSION_BUS_ADDRESS:-none}"

                __pl_start_xfconfd
                if ! __pl_start_wm; then
                    echo "WARNING: mini-session: no WM this round — will retry"
                    # Keep USER wallpaper visible (never solid purple)
                    __pl_set_wp || true
                    _wait=0
                    while [ ${'$'}_wait -lt 20 ]; do
                        __pl_logout && break 2
                        __pl_desktop_alive && break
                        # Re-paint wallpaper so root does not stay blank/X default
                        if [ ${'$'}(( _wait % 4 )) -eq 0 ]; then
                            __pl_set_wp || true
                            command -v xsetroot >/dev/null 2>&1 && xsetroot -cursor_name left_ptr 2>/dev/null || true
                        fi
                        sleep 0.5
                        _wait=${'$'}(( _wait + 1 ))
                    done
                    __pl_desktop_alive || continue
                fi

                __pl_start_panel
                __pl_signal_xfce_ready || true
                echo "mini-session v9: desktop stack up"

                _panel_retries=0
                while __pl_desktop_alive; do
                    if __pl_logout; then
                        echo "mini-session v9: user_logout while running"
                        break 2
                    fi
                    if __pl_xfconf_ok; then
                        if ! __pl_has xfce4-panel && command -v xfce4-panel >/dev/null 2>&1; then
                            if [ ${'$'}_panel_retries -lt 8 ]; then
                                _panel_retries=${'$'}(( _panel_retries + 1 ))
                                echo "mini-session: late-start xfce4-panel (try ${'$'}_panel_retries)"
                                __pl_start_panel_once || true
                            fi
                        fi
                        if ! __pl_has xfsettingsd && command -v xfsettingsd >/dev/null 2>&1; then
                            xfsettingsd --replace >/tmp/pl-xfsettingsd.log 2>&1 &
                        fi
                        if ! __pl_has xfwm4 && command -v xfwm4 >/dev/null 2>&1; then
                            if __pl_has openbox || __pl_has twm || __pl_has icewm; then
                                echo "mini-session: upgrading to xfwm4"
                                xfwm4 --replace >/tmp/pl-wm.log 2>&1 &
                                sleep 0.4
                            fi
                        fi
                    fi
                    sleep 0.4
                done
                echo "mini-session v9: components died — restarting in 1s"
                sleep 1
            done

            # teardown
            for _n in xfce4-panel xfdesktop xfsettingsd xfwm4 openbox matchbox-window-manager \
                fluxbox icewm metacity marco twm xfconfd; do
                for _c in /proc/[0-9]*/cmdline; do
                    [ -e "${'$'}_c" ] || continue
                    [ -r "${'$'}_c" ] || continue
                    _s=${'$'}(tr '\0' ' ' < "${'$'}_c" 2>/dev/null) || continue
                    case "${'$'}_s" in
                        *"${'$'}_n"*)
                            _pid=${'$'}(echo "${'$'}_c" | sed -n 's|.*/proc/\([0-9]*\)/cmdline|\1|p')
                            [ -n "${'$'}_pid" ] && kill "${'$'}_pid" 2>/dev/null || true
                            ;;
                    esac
                done
            done
            echo "mini-session v9: exited"
            exit 0
            """.trimIndent() + "\n"
        )
        bin.setReadable(true, false)
        bin.setExecutable(true, false)
        onLog("✓ XFCE mini-session v9 (overlay until xfdesktop)")
    } catch (e: Exception) {
        Log.e(ContainerRestoreEngine.TAG, "ensureArchXfceMiniSession: ${e.message}", e)
        onLog("! mini-session write failed: ${e.message}")
    }
}
