package com.sg.linuxgo.bootstrap

/** Part 2 of pocketlinux-launch shell. */
internal fun pocketLinuxLaunchScriptPart2(
    username: String,
    homeDir: String,
    selectedGuiMode: String,
    selectedDE: String,
    startCmd: String,
    gpuEnv: String
): String = """
        # Minimal openbox rc so the WM always has a valid config
        if [ ! -f "${'$'}LXQT_CONF/openbox/rc.xml" ] && [ ! -f "${'$'}LXQT_CONF/openbox/lxqt-rc.xml" ]; then
            cat > "${'$'}LXQT_CONF/openbox/lxqt-rc.xml" << 'OBEOF'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
  <theme><name>Clearlooks</name></theme>
  <keyboard><chainQuitKey>C-g</chainQuitKey></keyboard>
  <mouse><dragThreshold>8</dragThreshold></mouse>
  <desktops><number>1</number><firstdesk>1</firstdesk></desktops>
  <resize><drawContents>yes</drawContents></resize>
  <focus><focusNew>yes</focusNew></focus>
</openbox_config>
OBEOF
        fi

        # Hide PRoot-hostile LXQt autostart entries (power, network, locker)
        for svc in lxqt-powermanagement nm-applet blueman xscreensaver light-locker xfce4-power-manager; do
            cat > "${'$'}LXQT_CONF/autostart/${'$'}svc.desktop" << ASEOF
[Desktop Entry]
Hidden=true
ASEOF
        done

        # qterminal: never rewrite user prefs (colors/fonts/shell). Identity comes
        # from session env + /etc/bash.bashrc — same as a normal Linux desktop.
        mkdir -p "${'$'}LXQT_CONF/qterminal.org" 2>/dev/null || true

        echo "LXQt identity: USER=${'$'}USER HOME=${'$'}HOME XDG_CONFIG_HOME=${'$'}XDG_CONFIG_HOME whoami=$(whoami 2>/dev/null || echo '?') id_un=$(id -un 2>/dev/null || echo '?')"
        if command -v startlxqt >/dev/null 2>&1; then
            echo "LXQt: using startlxqt wrapper"
        elif command -v lxqt-session >/dev/null 2>&1; then
            echo "LXQt: using lxqt-session (startlxqt not found)"
        else
            echo "WARNING: neither startlxqt nor lxqt-session found in PATH"
        fi
    fi
    
    # Skip font cache rebuild at launch (already done during install)
    dbus-uuidgen --ensure 2>/dev/null || true

    # Start D-Bus (CRITICAL for GNOME/XFCE/MATE/LXQt)
    # /var/run must be a symlink to /run (Debian base-files). Never mkdir both.
    mkdir -p /run 2>/dev/null || true
    if [ -d /var/run ] && [ ! -L /var/run ]; then
        cp -a /var/run/. /run/ 2>/dev/null || true
        rm -rf /var/run 2>/dev/null || true
    fi
    if [ ! -e /var/run ]; then
        ln -sf /run /var/run 2>/dev/null || ln -s /run /var/run 2>/dev/null || true
    fi
    mkdir -p /run/dbus 2>/dev/null || true
    chmod 1777 /run/dbus 2>/dev/null || true
    rm -f /run/dbus/pid /var/run/dbus/pid 2>/dev/null
    dbus-uuidgen --ensure 2>/dev/null || true

    if ! grep -q "messagebus" /etc/passwd; then
        echo "messagebus:x:100:101:messagebus:/var/run/dbus:/usr/sbin/nologin" >> /etc/passwd 2>/dev/null || true
    fi

    # Ensure XDG_RUNTIME_DIR exists (xfconfd / xfsettingsd expect it).
    mkdir -p "${'$'}{XDG_RUNTIME_DIR:-/tmp}" 2>/dev/null || true
    chmod 700 "${'$'}{XDG_RUNTIME_DIR:-/tmp}" 2>/dev/null || true

    # ── D-Bus under Android rootless (proot / tawcroot) ─────────────────────
    # tawcroot: GDBus on *unix* sockets still tries SCM_CREDENTIALS (EXTERNAL)
    # even when the bus only advertises ANONYMOUS → xfconfd dies with
    # "Error sending credentials: Operation not permitted".
    # Fix: session+system buses over TCP localhost + ANONYMOUS (no SCM_CREDENTIALS).
    # proot: keep unix EXTERNAL+ANONYMOUS (works under ptrace proot).
    mkdir -p /usr/local/bin 2>/dev/null || true
    cat > /usr/local/bin/dbus-update-activation-environment << 'DBUSSTUB'
#!/bin/sh
# PocketLinux: no-op under rootless (anonymous/EXTERNAL quirks on Android).
exit 0
DBUSSTUB
    chmod 755 /usr/local/bin/dbus-update-activation-environment 2>/dev/null || true

    # Void: keep the xbps unpack wrapper if an xbps upgrade replaced /usr/bin.
    if [ -x /usr/local/bin/xbps-install ] && grep -q "unpack shim v" /usr/local/bin/xbps-install 2>/dev/null; then
        cp -f /usr/local/bin/xbps-install /usr/bin/xbps-install 2>/dev/null || true
        chmod 755 /usr/bin/xbps-install 2>/dev/null || true
    fi

    __pl_dbus_policy() {
        cat << 'DBUSPOL'
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
    <allow user="*"/>
    <allow send_type="method_call"/>
    <allow send_type="signal"/>
    <allow send_type="method_return"/>
    <allow send_type="error"/>
    <allow send_interface="org.freedesktop.DBus"/>
    <allow receive_interface="org.freedesktop.DBus"/>
    <allow send_interface="org.xfce.Xfconf"/>
    <allow receive_interface="org.xfce.Xfconf"/>
  </policy>
DBUSPOL
    }

    if [ "${'$'}{POCKETLINUX_RUNTIME:-}" = "tawcroot" ]; then
        # Fixed loopback ports (guest network is host network under tawcroot).
        PL_DBUS_SESS_PORT=18081
        PL_DBUS_SYS_PORT=18082
        # Free stale daemons from a previous session
        for _p in ${'$'}PL_DBUS_SESS_PORT ${'$'}PL_DBUS_SYS_PORT; do
            if command -v fuser >/dev/null 2>&1; then
                fuser -k "${'$'}_p/tcp" 2>/dev/null || true
            fi
        done
        cat > /tmp/pl-dbus-session.conf << DBUSCONF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>session</type>
  <keep_umask/>
  <listen>tcp:host=127.0.0.1,port=${'$'}PL_DBUS_SESS_PORT</listen>
  <auth>ANONYMOUS</auth>
  <allow_anonymous/>
  <standard_session_servicedirs/>
$(__pl_dbus_policy)
</busconfig>
DBUSCONF
        cat > /tmp/pl-dbus-system.conf << DBUSCONF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>session</type>
  <keep_umask/>
  <listen>tcp:host=127.0.0.1,port=${'$'}PL_DBUS_SYS_PORT</listen>
  <auth>ANONYMOUS</auth>
  <allow_anonymous/>
  <standard_session_servicedirs/>
$(__pl_dbus_policy)
</busconfig>
DBUSCONF
        export DBUS_SESSION_BUS_ADDRESS=tcp:host=127.0.0.1,port=${'$'}PL_DBUS_SESS_PORT
        export DBUS_SYSTEM_BUS_ADDRESS=tcp:host=127.0.0.1,port=${'$'}PL_DBUS_SYS_PORT
        dbus-daemon --config-file=/tmp/pl-dbus-session.conf --fork --nopidfile 2>/tmp/pl-dbus-sess-err.log || true
        dbus-daemon --config-file=/tmp/pl-dbus-system.conf --fork --nopidfile 2>/tmp/pl-dbus-sys-err.log || true
        sleep 0.2
        # Probe TCP with dbus-send if available
        _sess_ok=0
        if command -v dbus-send >/dev/null 2>&1; then
            if dbus-send --session --print-reply --dest=org.freedesktop.DBus \
                /org/freedesktop/DBus org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
                _sess_ok=1
            fi
        else
            # no dbus-send: assume up if daemon left no error
            [ ! -s /tmp/pl-dbus-sess-err.log ] && _sess_ok=1
        fi
        if [ "${'$'}_sess_ok" = "1" ]; then
            echo "D-Bus session: ready (TCP ANONYMOUS tawcroot) ${'$'}DBUS_SESSION_BUS_ADDRESS"
        else
            echo "WARNING: D-Bus TCP session failed"
            cat /tmp/pl-dbus-sess-err.log 2>/dev/null | tail -5 || true
        fi
        echo "D-Bus system: ${'$'}DBUS_SYSTEM_BUS_ADDRESS"
        # Optional unix alias for hard-coded clients (best-effort; may still hit SCM issues)
        mkdir -p /run/dbus 2>/dev/null || true
    else
        # proot path: unix sockets + EXTERNAL
        __pl_dbus_write_conf() {
            cat > "${'$'}1" << DBUSCONF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>session</type>
  <keep_umask/>
  <listen>unix:path=${'$'}2</listen>
  <auth>EXTERNAL</auth>
  <auth>ANONYMOUS</auth>
  <allow_anonymous/>
  <standard_session_servicedirs/>
$(__pl_dbus_policy)
</busconfig>
DBUSCONF
        }
        __pl_dbus_start() {
            rm -f "${'$'}2" 2>/dev/null
            [ -f "${'$'}1" ] || return 1
            dbus-daemon --config-file="${'$'}1" --fork --nopidfile 2>/dev/null || return 1
            _i=0
            while [ ${'$'}_i -lt 30 ]; do
                if [ -S "${'$'}2" ] || [ -e "${'$'}2" ]; then return 0; fi
                sleep 0.05
                _i=${'$'}(( _i + 1 ))
            done
            return 1
        }
        PL_DBUS_SESSION_SOCK=/tmp/dbus-session
        PL_DBUS_SESSION_CONF=/tmp/pl-dbus-session.conf
        __pl_dbus_write_conf "${'$'}PL_DBUS_SESSION_CONF" "${'$'}PL_DBUS_SESSION_SOCK"
        export DBUS_SESSION_BUS_ADDRESS=unix:path=${'$'}PL_DBUS_SESSION_SOCK
        if __pl_dbus_start "${'$'}PL_DBUS_SESSION_CONF" "${'$'}PL_DBUS_SESSION_SOCK"; then
            echo "D-Bus session: ready (EXTERNAL+ANONYMOUS) at ${'$'}PL_DBUS_SESSION_SOCK"
        else
            dbus-daemon --session --address=${'$'}DBUS_SESSION_BUS_ADDRESS --fork --nopidfile 2>/dev/null || true
            echo "D-Bus session: stock fallback"
        fi
        if [ -n "${'$'}XDG_RUNTIME_DIR" ] && [ -S "${'$'}PL_DBUS_SESSION_SOCK" ]; then
            ln -sfn "${'$'}PL_DBUS_SESSION_SOCK" "${'$'}XDG_RUNTIME_DIR/bus" 2>/dev/null || true
        fi
        mkdir -p /run/dbus 2>/dev/null || true
        rm -f /tmp/pl-system-bus /run/dbus/pid /var/run/dbus/pid 2>/dev/null
        rm -f /run/dbus/system_bus_socket 2>/dev/null
        if [ ! -L /var/run ]; then
            mkdir -p /var/run/dbus 2>/dev/null || true
            rm -f /var/run/dbus/system_bus_socket 2>/dev/null
        fi
        SYS_OK=0
        PL_DBUS_SYS_CONF=/tmp/pl-dbus-system.conf
        __pl_dbus_write_conf "${'$'}PL_DBUS_SYS_CONF" /run/dbus/system_bus_socket
        if __pl_dbus_start "${'$'}PL_DBUS_SYS_CONF" /run/dbus/system_bus_socket; then
            export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
            SYS_OK=1
            echo "D-Bus system: ready at /run/dbus/system_bus_socket"
        else
            __pl_dbus_write_conf "${'$'}PL_DBUS_SYS_CONF" /tmp/pl-system-bus
            if __pl_dbus_start "${'$'}PL_DBUS_SYS_CONF" /tmp/pl-system-bus; then
                ln -sfn /tmp/pl-system-bus /run/dbus/system_bus_socket 2>/dev/null || true
                export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/tmp/pl-system-bus
                SYS_OK=1
                echo "D-Bus system: proot-fallback ${'$'}DBUS_SYSTEM_BUS_ADDRESS"
            fi
        fi
        if [ "${'$'}SYS_OK" != "1" ]; then
            export DBUS_SYSTEM_BUS_ADDRESS=${'$'}DBUS_SESSION_BUS_ADDRESS
            ln -sfn /tmp/dbus-session /run/dbus/system_bus_socket 2>/dev/null || true
            echo "D-Bus system: session-reuse ${'$'}DBUS_SYSTEM_BUS_ADDRESS"
        fi
        ls -la /run/dbus/system_bus_socket /tmp/dbus-session /tmp/pl-system-bus 2>/dev/null | head -8 || true
    fi

    # ── Audio Support ──
    # X11 + Wayland: host PulseAudio (Kotlin HostPulseAudioServer / Rust pulse_server)
    # on tcp:127.0.0.1:14713 with AAudio sink. Guest must NOT run its own daemon or
    # clients stay on a silent local null sink.
    PA_CONF_DIR=$homeDir/.config/pulse
    mkdir -p ${'$'}PA_CONF_DIR 2>/dev/null
    # Drop any leftover guest daemon/runtime so libpulse prefers the host bridge.
    unset PULSE_SERVER 2>/dev/null || true
    if command -v pulseaudio >/dev/null 2>&1; then
        pulseaudio -k 2>/dev/null || true
    fi
    if command -v pipewire >/dev/null 2>&1; then
        pkill -x pipewire 2>/dev/null || true
        pkill -x pipewire-pulse 2>/dev/null || true
        pkill -x wireplumber 2>/dev/null || true
    fi
    rm -rf /tmp/pulse-* /tmp/pulse-runtime /tmp/pulse-state 2>/dev/null || true
    export PULSE_SERVER=tcp:127.0.0.1:14713
    cat > ${'$'}PA_CONF_DIR/client.conf << 'PACLIENT'
autospawn = no
default-server = tcp:127.0.0.1:14713
PACLIENT
    # Avoid stale default.pa from older X11 guest-PA sessions.
    rm -f ${'$'}PA_CONF_DIR/default.pa 2>/dev/null || true
    echo "PulseAudio: host bridge PULSE_SERVER=${'$'}PULSE_SERVER (mode=${'$'}GUI_MODE)"
    # Wait briefly for Android host daemon (started before guest launch).
    __pa_host_ok=0
    for _pa_w in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        if command -v python3 >/dev/null 2>&1; then
            if python3 -c "import socket;s=socket.socket();s.settimeout(0.3);s.connect(('127.0.0.1',14713));s.close()" 2>/dev/null; then
                __pa_host_ok=1
                break
            fi
        elif (echo > /dev/tcp/127.0.0.1/14713) >/dev/null 2>&1; then
            __pa_host_ok=1
            break
        fi
        sleep 0.25
    done
    if [ "${'$'}__pa_host_ok" = "1" ]; then
        echo "PulseAudio: host daemon reachable on 127.0.0.1:14713"
    else
        echo "WARNING: host PulseAudio not reachable on 127.0.0.1:14713 — audio may be silent"
    fi

    # NOTE: xfconf-query and gsettings compositing disables are now inside
    # the X11 block below, where DISPLAY=:0 is set and the X server is ready.
    # Previously they ran here with DISPLAY=:1 and had no effect in X11 mode.

    if [ "${'$'}GUI_MODE" = "x11" ]; then
        # Lorie listens on filesystem socket under host TMPDIR → guest /tmp/.X11-unix/X0.
        # Abstract sockets are NOT reachable under PRoot — DISPLAY must be :0 with X0 present.
        export DISPLAY=:0
        mkdir -p /tmp/.X11-unix 2>/dev/null || true
        chmod 1777 /tmp/.X11-unix 2>/dev/null || true
        echo "Waiting for embedded X11 server (filesystem socket + display)..."
        X11_READY=0
        for _x11_try in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60; do
            if [ -S /tmp/.X11-unix/X0 ] || [ -e /tmp/.X11-unix/X0 ]; then
                if command -v xdpyinfo >/dev/null 2>&1 && xdpyinfo -display :0 >/dev/null 2>&1; then
                    X11_READY=1
                    echo "✓ X11 ready (socket + xdpyinfo) try=${'$'}_x11_try"
                    break
                elif command -v xset >/dev/null 2>&1 && xset q >/dev/null 2>&1; then
                    X11_READY=1
                    echo "✓ X11 ready (socket + xset) try=${'$'}_x11_try"
                    break
                fi
                # Socket exists but client not ready yet — keep polling
            fi
            sleep 0.25
        done
        echo "X11 socket listing:"
        ls -la /tmp/.X11-unix 2>&1 || echo "(no /tmp/.X11-unix)"
        if [ "${'$'}X11_READY" = "0" ]; then
            echo "FATAL: X11 display :0 not reachable (no working /tmp/.X11-unix/X0)."
            echo "Usually Arch absolute XKB symlink broke Lorie, or X server failed."
            echo "Relaunch GUI after the app fixed XKB, or check x11_server.log."
            exit 1
        fi

        # Desktop identity for the session (must match the DE being started)
        if echo "$startCmd" | grep -q "startplasma"; then
            export XDG_CURRENT_DESKTOP=KDE
            export XDG_SESSION_DESKTOP=KDE
        elif echo "$startCmd" | grep -q "mate-session"; then
            export XDG_CURRENT_DESKTOP=MATE
            export XDG_SESSION_DESKTOP=mate
            # MATE needs gvfs trash:// — local-only VFS makes Trash "not available".
            unset GIO_USE_VFS 2>/dev/null || true
            export GIO_USE_VOLUME_MONITOR=unix
            export GVFS_DISABLE_FUSE=1
            export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1
        elif echo "$startCmd" | grep -q "startlxqt\|lxqt-session"; then
            export XDG_CURRENT_DESKTOP=LXQt
            export XDG_SESSION_DESKTOP=lxqt
            export XDG_MENU_PREFIX=lxqt-
            export QT_QPA_PLATFORM=xcb
            export QT_XCB_GL_INTEGRATION=none
        elif echo "$startCmd" | grep -q "gnome-session"; then
            export XDG_CURRENT_DESKTOP=GNOME
            export XDG_SESSION_DESKTOP=gnome
        else
            export XDG_CURRENT_DESKTOP="${'$'}{XDG_CURRENT_DESKTOP:-XFCE}"
            export XDG_SESSION_DESKTOP="${'$'}{XDG_SESSION_DESKTOP:-xfce}"
        fi

        # Prefs already on disk (xfwm4/thunar xml). Skip slow xfconf-query spam at boot.
        # Display zoom is Android/Lorie-only (Termux X11 style). Do NOT rewrite guest
        # GDK/Qt/Xft DPI from POCKETLINUX_SCALE — that only changes fonts unevenly and
        # softens quality when combined with viewer scale. Force guest toolkit to 1×.
        unset QT_SCALE_FACTOR 2>/dev/null || true
        unset QT_FONT_DPI 2>/dev/null || true
        export GDK_SCALE=1
        export GDK_DPI_SCALE=1
        echo "Xft.dpi: 96" | xrdb -merge 2>/dev/null || true
        # Do NOT delete stock wallpapers under /usr/share/backgrounds (incl. xfce/).
        # An older launch path purged everything except pocketlinux wallpaper files,
        # which emptied Desktop Settings → Background → "xfce" when the branded file
        # was missing or not under backgrounds/xfce/.
        # Do NOT wipe ~/.cache/thumbnails every boot — forces slow re-decode under PRoot.

        # Wallpaper immediately — do not wait for xfdesktop (was ~25–30s grey/black).
        # xfwm4 clears the root window on start, so re-assert for ~12s until xfdesktop owns it.
        # user_wp_only: never pick pocketlinux_wp when xfconf/XML has another last-image.
        WP=""
        _WP_BRAND=""
        __pl_consider_xfce_wp() {
            _v=${'$'}(printf '%s' "${'$'}1" | sed "s/^'//;s/'$//")
            [ -n "${'$'}_v" ] && [ -f "${'$'}_v" ] || return 1
            case "${'$'}_v" in
                *pocketlinux_wp*|*pocketlinux-wallpaper*)
                    [ -z "${'$'}_WP_BRAND" ] && _WP_BRAND="${'$'}_v"
                    ;;
                *)
                    [ -z "${'$'}WP" ] && WP="${'$'}_v"
                    ;;
            esac
            return 0
        }
        if command -v xfconf-query >/dev/null 2>&1; then
            for prop in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null); do
                if echo "${'$'}prop" | grep -q "last-image"; then
                    __pl_consider_xfce_wp "${'$'}(xfconf-query -c xfce4-desktop -p "${'$'}prop" 2>/dev/null)"
                fi
            done
        fi
        _xml="${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
        if [ -z "${'$'}WP" ] && [ -f "${'$'}_xml" ]; then
            _tmp=/tmp/pl-last-image.${'$'}$
            sed -n 's/.*name="last-image"[^>]*value="\([^"]*\)".*/\1/p' "${'$'}_xml" > "${'$'}_tmp" 2>/dev/null || true
            while IFS= read -r _v || [ -n "${'$'}_v" ]; do
                __pl_consider_xfce_wp "${'$'}_v"
            done < "${'$'}_tmp"
            rm -f "${'$'}_tmp"
        fi
        [ -z "${'$'}WP" ] && [ -n "${'$'}_WP_BRAND" ] && WP="${'$'}_WP_BRAND"
        if [ -z "${'$'}WP" ]; then
            # First-boot only: branded files, then stock XFCE. Skip if xfconf
            # already named a backdrop (including pocketlinux as the user's current choice).
            mkdir -p /usr/share/xfce4/backdrops /usr/share/backgrounds/xfce 2>/dev/null || true
            mkdir -p /usr/share/images/desktop-base 2>/dev/null || true
            for f in \
                /usr/share/xfce4/backdrops/* \
                /usr/share/images/desktop-base/* \
                /usr/share/backgrounds/xfce/xfce-blue.jpg \
                /usr/share/backgrounds/xfce/*.jpg \
                /usr/share/backgrounds/xfce/*.png \
                /usr/share/backgrounds/*; do
                if [ -f "${'$'}f" ]; then
                    case "${'$'}f" in
                        *.svg|*pocketlinux_wp*|*pocketlinux-wallpaper*) ;;
                        *)
                            WP="${'$'}f"
                            break
                            ;;
                    esac
                fi
            done
        fi
        __pl_set_wp() {
            if [ -n "${'$'}WP" ] && command -v feh >/dev/null 2>&1; then
                feh --no-fehbg --bg-fill "${'$'}WP" 2>/dev/null || feh --bg-fill "${'$'}WP" 2>/dev/null || return 1
                return 0
            fi
            if [ -n "${'$'}WP" ] && command -v xwallpaper >/dev/null 2>&1; then
                xwallpaper --zoom "${'$'}WP" 2>/dev/null || return 1
                return 0
            fi
            if [ -n "${'$'}WP" ] && command -v hsetroot >/dev/null 2>&1; then
                hsetroot -fill "${'$'}WP" 2>/dev/null || return 1
                return 0
            fi
            # Solid interim only — avoid long slate grey if image tools missing
            if command -v xsetroot >/dev/null 2>&1; then
                xsetroot -solid "#000000" 2>/dev/null || true
            fi
            return 1
        }
        # MATE wallpaper: ONLY the path the user has in gsettings — never hardcode
        # pocketlinux_wp. Apply via feh as interim + re-touch gsettings so m-s-d paints.
        __PL_IS_MATE=0
        if echo "$startCmd" | grep -q "mate-session" || [ "$selectedDE" = "mate" ]; then
            __PL_IS_MATE=1
        fi
        __pl_mate_user_wallpaper() {
            # Prints absolute path of user-selected wallpaper, or empty.
            _raw=""
            if command -v gsettings >/dev/null 2>&1; then
                if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                    _raw=${'$'}(gsettings get org.mate.background picture-filename 2>/dev/null || true)
                elif command -v dbus-run-session >/dev/null 2>&1; then
                    _raw=${'$'}(dbus-run-session -- gsettings get org.mate.background picture-filename 2>/dev/null || true)
                fi
            fi
            # gsettings returns 'path' or nothing
            _raw=${'$'}(printf '%s' "${'$'}_raw" | sed "s/^'//;s/'$//")
            if [ -n "${'$'}_raw" ] && [ -f "${'$'}_raw" ]; then
                printf '%s' "${'$'}_raw"
            fi
        }
        __pl_mate_paint_user_wallpaper() {
            # Re-apply whatever the user already chose — do not write a new path.
            _uwp=${'$'}(__pl_mate_user_wallpaper)
            if [ -z "${'$'}_uwp" ]; then
                echo "Wallpaper: MATE — no user picture-filename set yet"
                return 1
            fi
            if command -v gsettings >/dev/null 2>&1; then
                if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                    gsettings set org.mate.background draw-background true 2>/dev/null || true
                    # Same path → forces mate-settings-daemon to refresh without changing choice
                    gsettings set org.mate.background picture-filename "${'$'}_uwp" 2>/dev/null || true
                elif command -v dbus-run-session >/dev/null 2>&1; then
                    dbus-run-session -- gsettings set org.mate.background draw-background true 2>/dev/null || true
                    dbus-run-session -- gsettings set org.mate.background picture-filename "${'$'}_uwp" 2>/dev/null || true
                fi
            fi
            if command -v feh >/dev/null 2>&1; then
                feh --no-fehbg --bg-fill "${'$'}_uwp" 2>/dev/null || feh --bg-fill "${'$'}_uwp" 2>/dev/null || true
            elif command -v xwallpaper >/dev/null 2>&1; then
                xwallpaper --zoom "${'$'}_uwp" 2>/dev/null || true
            fi
            echo "Wallpaper: MATE user backdrop → ${'$'}_uwp"
            return 0
        }
        if [ "${'$'}__PL_IS_MATE" = "1" ]; then
            if ! __pl_mate_paint_user_wallpaper; then
                if command -v xsetroot >/dev/null 2>&1; then
                    xsetroot -solid "#1e1e1e" 2>/dev/null || true
                fi
            fi
            # Fast re-assert (~first 6s only): WM clears root on start; keep painting
            # the *user* path so wallpaper is not blank for 20–30s. Never inject a
            # fixed PocketLinux image — only whatever gsettings already has.
            nohup sh -c '
                export DISPLAY="${'$'}{DISPLAY:-:0}"
                export HOME="${'$'}{HOME}"
                export XDG_CONFIG_HOME="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
                export DBUS_SESSION_BUS_ADDRESS="${'$'}{DBUS_SESSION_BUS_ADDRESS-}"
                _get_wp() {
                    _r=""
                    if command -v gsettings >/dev/null 2>&1; then
                        if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                            _r=${'$'}(gsettings get org.mate.background picture-filename 2>/dev/null || true)
                        elif command -v dbus-run-session >/dev/null 2>&1; then
                            _r=${'$'}(dbus-run-session -- gsettings get org.mate.background picture-filename 2>/dev/null || true)
                        fi
                    fi
                    # strip surrounding quotes from gsettings output
                    _r=${'$'}(printf %s "${'$'}_r" | tr -d \"\\047\")
                    if [ -n "${'$'}_r" ] && [ -f "${'$'}_r" ]; then
                        printf %s "${'$'}_r"
                    fi
                }
                _paint() {
                    _p="${'$'}1"
                    [ -n "${'$'}_p" ] && [ -f "${'$'}_p" ] || return 1
                    if command -v feh >/dev/null 2>&1; then
                        feh --no-fehbg --bg-fill "${'$'}_p" 2>/dev/null || true
                    elif command -v xwallpaper >/dev/null 2>&1; then
                        xwallpaper --zoom "${'$'}_p" 2>/dev/null || true
                    fi
                }
                i=0
                while [ "${'$'}i" -lt 16 ]; do
                    _raw=${'$'}(_get_wp)
                    if [ -n "${'$'}_raw" ]; then
                        _paint "${'$'}_raw"
                        if command -v pgrep >/dev/null 2>&1; then
                            if pgrep -x mate-settings-daemon >/dev/null 2>&1 \
                                || pgrep -f "[m]ate-settings-daemon" >/dev/null 2>&1 \
                                || pgrep -x marco >/dev/null 2>&1; then
                                if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                                    gsettings set org.mate.background draw-background true 2>/dev/null || true
                                    gsettings set org.mate.background picture-filename "${'$'}_raw" 2>/dev/null || true
                                fi
                                _paint "${'$'}_raw"
                                # Keep going a few more ticks after WM up (root clear races)
                                if [ "${'$'}i" -ge 4 ]; then
                                    echo "Wallpaper: MATE quick-assert done → ${'$'}_raw"
                                    exit 0
                                fi
                            fi
                        fi
                    fi
                    sleep 0.4
                    i=${'$'}((i + 1))
                done
            ' >/dev/null 2>&1 &
        elif __pl_set_wp; then
            echo "Wallpaper ready: ${'$'}WP"
        else
            if [ -n "${'$'}WP" ]; then
                echo "Wallpaper interim solid (no feh); image=${'$'}WP — install: pacman -S feh"
            else
                echo "Wallpaper interim solid (no image file found)"
            fi
            # One-shot background install so the *next* boot has instant image wallpaper
            if [ -f /var/lib/pocketlinux/need_feh ] || ! command -v feh >/dev/null 2>&1; then
                if command -v pacman >/dev/null 2>&1; then
                    nohup sh -c 'pacman -S --noconfirm --needed feh >/dev/null 2>&1 && rm -f /var/lib/pocketlinux/need_feh && echo feh-installed' >/dev/null 2>&1 &
                    echo "Wallpaper: installing feh in background (next boot will be instant)"
                elif command -v apt-get >/dev/null 2>&1; then
                    nohup sh -c 'export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends feh >/dev/null 2>&1 && rm -f /var/lib/pocketlinux/need_feh && echo feh-installed' >/dev/null 2>&1 &
                    echo "Wallpaper: installing feh in background (next boot will be instant)"
                fi
            fi
        fi
        # ── MATE: safety + first-boot seed only (do NOT reset user theme/wallpaper) ──
        # Earlier builds re-applied pocketlinux wallpaper + TraditionalOk/Adwaita every
        # boot via gsettings — that wiped Appearance changes on restart.
        if echo "$startCmd" | grep -q "mate-session" || [ "$selectedDE" = "mate" ]; then
            mkdir -p /usr/share/glib-2.0/schemas /var/lib/pocketlinux \
                "${'$'}HOME/.config/mate" 2>/dev/null || true
            # v2: also strips gschema wallpaper defaults; dual marker so /root vs /home is safe
            _mate_seed="${'$'}HOME/.config/mate/pocketlinux_user_prefs_seeded_v2"
            _mate_seed_sys=/var/lib/pocketlinux/mate_user_prefs_seeded_v2
            _mate_wp="${'$'}WP"
            if [ -z "${'$'}_mate_wp" ] || [ ! -f "${'$'}_mate_wp" ]; then
                for _f in \
                    /usr/share/backgrounds/pocketlinux_wp.png \
                    /usr/share/backgrounds/xfce/pocketlinux_wp.png \
                    /usr/share/xfce4/backdrops/pocketlinux_wp.png \
                    /usr/share/backgrounds/pocketlinux-wallpaper.jpg \
                    /usr/share/backgrounds/pocketlinux-wallpaper.png; do
                    if [ -f "${'$'}_f" ]; then
                        _mate_wp="${'$'}_f"
                        break
                    fi
                done
            fi
            [ -z "${'$'}_mate_wp" ] && _mate_wp=/usr/share/backgrounds/pocketlinux_wp.png
            # Prefer TraditionalOk from mate-themes; fall back to other Metacity themes.
            _marco_theme=TraditionalOk
            if [ ! -d /usr/share/themes/TraditionalOk/metacity-1 ] && [ ! -d /usr/share/themes/TraditionalOk/marco-1 ]; then
                for _t in Menta BlueMenta BlackMATE GreenLaguna Default; do
                    if [ -d "/usr/share/themes/${'$'}_t/metacity-1" ] || [ -d "/usr/share/themes/${'$'}_t/marco-1" ]; then
                        _marco_theme="${'$'}_t"
                        break
                    fi
                done
            fi
            # System defaults: NEVER pin wallpaper or gtk-theme here — those are user prefs.
            # Install-time 99_pocketlinux.gschema.override used to force pocketlinux wallpaper
            # + Adwaita-dark; if dconf is empty/unavailable, every session looked "reset".
            __pl_sanitize_mate_gschema() {
                _gsf="${'$'}1"
                [ -f "${'$'}_gsf" ] || return 0
                # Drop wallpaper + gtk-theme lines that stomp Appearance on default fallback
                if grep -qE "picture-filename=|gtk-theme=|icon-theme=" "${'$'}_gsf" 2>/dev/null; then
                    sed -i \
                        -e "/^picture-filename=/d" \
                        -e "/^picture-options=/d" \
                        -e "/^draw-background=/d" \
                        -e "/^gtk-theme=/d" \
                        -e "/^icon-theme=/d" \
                        -e "/^\[org\.mate\.background\]/d" \
                        "${'$'}_gsf" 2>/dev/null || true
                    echo "MATE: stripped wallpaper/gtk-theme defaults from ${'$'}(basename "${'$'}_gsf")"
                    return 0
                fi
                return 1
            }
            _gs_changed=0
            if [ ! -f /usr/share/glib-2.0/schemas/99_pocketlinux_mate.gschema.override ]; then
                cat > /usr/share/glib-2.0/schemas/99_pocketlinux_mate.gschema.override << MATEGS
[org.mate.interface]
gtk-decoration-layout='menu:minimize,maximize,close'
gtk-dialogs-use-header=false

[org.mate.Marco.general]
theme='${'$'}_marco_theme'
button-layout='menu:minimize,maximize,close'
compositing-manager=false
num-workspaces=1
center-new-windows=true
mouse-button-modifier='<Alt>'
raise-on-click=true
focus-mode='click'

[org.mate.session.required-components]
windowmanager='marco'
MATEGS
                _gs_changed=1
            fi
            for _gsf in \
                /usr/share/glib-2.0/schemas/99_pocketlinux.gschema.override \
                /usr/share/glib-2.0/schemas/99_pocketlinux_mate.gschema.override; do
                if __pl_sanitize_mate_gschema "${'$'}_gsf"; then
                    _gs_changed=1
                fi
                # Fix GTK Adwaita wrongly used as Marco (Metacity) theme
                if [ -f "${'$'}_gsf" ] && grep -q "theme='Adwaita" "${'$'}_gsf" 2>/dev/null; then
                    sed -i "s/theme='Adwaita-dark'/theme='${'$'}_marco_theme'/g; s/theme='Adwaita'/theme='${'$'}_marco_theme'/g" \
                        "${'$'}_gsf" 2>/dev/null || true
                    _gs_changed=1
                fi
            done
            if [ "${'$'}_gs_changed" = "1" ] && command -v glib-compile-schemas >/dev/null 2>&1; then
                glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
            fi
""".trimIndent()
