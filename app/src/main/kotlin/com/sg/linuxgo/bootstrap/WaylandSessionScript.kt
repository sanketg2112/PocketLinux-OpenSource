package com.sg.linuxgo.bootstrap

fun buildWaylandSessionScript(startCmd: String): String = """
    #!/bin/sh
    echo "PocketLinux Wayland Session Starter"
    echo "Waiting for compositor to resize..."
    sleep 1.2

    # tawc-compat guest profile (GTK3 menus / graphics / toolkit backends)
    if [ -f /etc/profile.d/pocketlinux-tawc-wayland.sh ]; then
        # shellcheck disable=SC1091
        . /etc/profile.d/pocketlinux-tawc-wayland.sh
        echo "tawc-compat: gfx=${'$'}{POCKETLINUX_WAYLAND_GFX:-mesa} gtk3_menus=${'$'}{POCKETLINUX_GTK3_MENUS_WORKAROUND:-1}"
    fi
    if [ -f /etc/profile.d/zz-pocketlinux-hybris.sh ]; then
        # shellcheck disable=SC1091
        . /etc/profile.d/zz-pocketlinux-hybris.sh
    fi

    # Detect XWayland display (labwc starts Xwayland; socket under /tmp/.X11-unix)
    XDISPLAY=""
    for _n in 0 1 2 3 4 5; do
        if [ -e "/tmp/.X11-unix/X${'$'}_n" ] || [ -S "/tmp/.X11-unix/X${'$'}_n" ]; then
            XDISPLAY=":${'$'}_n"
            break
        fi
    done
    if [ -z "${'$'}XDISPLAY" ]; then
        # Wait briefly for labwc's Xwayland
        _w=0
        while [ ${'$'}_w -lt 30 ]; do
            for _n in 0 1 2 3 4 5; do
                if [ -e "/tmp/.X11-unix/X${'$'}_n" ] || [ -S "/tmp/.X11-unix/X${'$'}_n" ]; then
                    XDISPLAY=":${'$'}_n"
                    break 2
                fi
            done
            sleep 0.2
            _w=${'$'}(( _w + 1 ))
        done
    fi
    if [ -z "${'$'}XDISPLAY" ]; then
        XDISPLAY=":0"
        echo "WARNING: XWayland socket not found yet; DISPLAY=:0 (may be abstract)"
    fi
    export DISPLAY="${'$'}XDISPLAY"
    export WAYLAND_DISPLAY="${'$'}{WAYLAND_DISPLAY:-wayland-0}"
    export XDG_SESSION_TYPE=wayland
    # Prefer native Wayland toolkits; X11-only apps use XWayland via DISPLAY.
    export GDK_BACKEND="${'$'}{GDK_BACKEND:-wayland,x11}"
    export QT_QPA_PLATFORM="${'$'}{QT_QPA_PLATFORM:-wayland;xcb}"
    export SDL_VIDEODRIVER="${'$'}{SDL_VIDEODRIVER:-wayland,x11}"
    export XCURSOR_THEME=Adwaita
    export XCURSOR_SIZE=12

    echo "Using XWayland display: ${'$'}DISPLAY WAYLAND_DISPLAY=${'$'}WAYLAND_DISPLAY"

    # Set EWMH properties on root window to prevent XFCE polling storms
    if command -v xprop >/dev/null 2>&1; then
        xprop -root -f _NET_NUMBER_OF_DESKTOPS 32c -set _NET_NUMBER_OF_DESKTOPS 1 2>/dev/null || true
        xprop -root -f _NET_CURRENT_DESKTOP 32c -set _NET_CURRENT_DESKTOP 0 2>/dev/null || true
    fi
    if command -v xfconf-query >/dev/null 2>&1; then
        xfconf-query -c xfwm4 -p /general/workspace_count -s 1 2>/dev/null || true
        
        # Configure XFCE panel pager plugin to show miniature view (small squares) instead of workspace name
        for prop in ${'$'}(xfconf-query -c xfce4-panel -p /plugins -l 2>/dev/null); do
            if xfconf-query -c xfce4-panel -p "${'$'}prop" 2>/dev/null | grep -q "pager"; then
                plugin_dir=${'$'}(echo "${'$'}prop" | cut -d'/' -f1-3)
                xfconf-query -c xfce4-panel -p "${'$'}plugin_dir/miniature-view" -n -t bool -s true 2>/dev/null || true
            fi
        done
    fi

    echo "Starting desktop session: $startCmd"

    # Launch swaybg immediately as the wallpaper layer (before DE starts)
    WALLPAPER=""
    if command -v xfconf-query >/dev/null 2>&1; then
        for prop in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null); do
            if echo "${'$'}prop" | grep -q "last-image"; then
                val=${'$'}(xfconf-query -c xfce4-desktop -p "${'$'}prop" 2>/dev/null)
                if [ -f "${'$'}val" ]; then
                    WALLPAPER="${'$'}val"
                    break
                fi
            fi
        done
    fi
    # swaybg runs as a Wayland layer-shell background — visible immediately
    if command -v swaybg >/dev/null 2>&1; then
        if [ -n "${'$'}WALLPAPER" ]; then
            swaybg -i "${'$'}WALLPAPER" -m fill 2>/dev/null &
        else
            swaybg -c "#000000" 2>/dev/null &
        fi
    fi

    # Start session in background so we can keep panel alive
    if command -v dbus-run-session >/dev/null 2>&1; then
        dbus-run-session -- $startCmd &
    else
        $startCmd &
    fi
    SESSION_PID=${'$'}!

    # Wait for session to initialize, then apply wallpaper to xfconf too
    sleep 4

    # Apply wallpaper via xfconf for XFCE apps that read it
    if [ -n "${'$'}WALLPAPER" ] && command -v xfconf-query >/dev/null 2>&1; then
        for prop in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null); do
            if echo "${'$'}prop" | grep -q "last-image"; then
                xfconf-query -c xfce4-desktop -p "${'$'}prop" -s "${'$'}WALLPAPER" 2>/dev/null || true
            fi
        done
        # Set for common monitor names
        for mon in monitor0 monitorVirtual-0 monitorVirtual-1 monitorXWAYLAND0 monitorXWAYLAND1; do
            for ws in 0 1 2 3; do
                xfconf-query -c xfce4-desktop -p "/backdrop/screen0/${'$'}{mon}/workspace${'$'}{ws}/last-image" -n -t string -s "${'$'}WALLPAPER" 2>/dev/null || true
                xfconf-query -c xfce4-desktop -p "/backdrop/screen0/${'$'}{mon}/workspace${'$'}{ws}/image-style" -n -t int -s 5 2>/dev/null || true
            done
        done
    fi

    # Set cursor theme via xfconf
    if command -v xfconf-query >/dev/null 2>&1; then
        xfconf-query -c xsettings -p /Gtk/CursorThemeName -n -t string -s "Adwaita" 2>/dev/null || true
        xfconf-query -c xsettings -p /Gtk/CursorThemeSize -n -t int -s 12 2>/dev/null || true
    fi

    # Start a background watchdog to keep xfce4-panel alive
    (
        while true; do
            if ! pgrep -x xfce4-panel >/dev/null; then
                DISPLAY="${'$'}DISPLAY" WAYLAND_DISPLAY=wayland-0 xfce4-panel &
            fi
            sleep 5
        done
    ) >/dev/null 2>&1 &

    # Sync XFCE xsettings → GTK settings.ini only when values actually change.
    # Empty xfconf (D-Bus blip) must not rewrite icons to PocketLinux — that
    # flipped the panel menu between icon packs and hitching GL apps.
    (
        sleep 5
        LAST_KEY=""
        while true; do
            THEME=${"$"}(xfconf-query -c xsettings -p /Net/ThemeName 2>/dev/null)
            ICON=${"$"}(xfconf-query -c xsettings -p /Net/IconThemeName 2>/dev/null)
            CURSOR=${"$"}(xfconf-query -c xsettings -p /Gtk/CursorThemeName 2>/dev/null)
            CURSOR_SIZE=${"$"}(xfconf-query -c xsettings -p /Gtk/CursorThemeSize 2>/dev/null)
            if [ -z "${"$"}THEME" ] && [ -z "${"$"}ICON" ]; then
                xfconf-query -c xsettings -m >/dev/null 2>&1
                sleep 1
                continue
            fi
            KEY="${"$"}THEME|${"$"}ICON|${"$"}CURSOR|${"$"}CURSOR_SIZE"
            if [ "${"$"}KEY" != "${"$"}LAST_KEY" ]; then
                LAST_KEY="${"$"}KEY"
                if [ -n "${"$"}{THEME}" ]; then
                    gsettings set org.gnome.desktop.interface gtk-theme "${"$"}{THEME}" 2>/dev/null || true
                fi
                if [ -n "${"$"}{ICON}" ]; then
                    gsettings set org.gnome.desktop.interface icon-theme "${"$"}{ICON}" 2>/dev/null || true
                fi
                if [ -n "${"$"}{CURSOR}" ]; then
                    gsettings set org.gnome.desktop.interface cursor-theme "${"$"}{CURSOR}" 2>/dev/null || true
                fi
                if [ -n "${"$"}{CURSOR_SIZE}" ]; then
                    gsettings set org.gnome.desktop.interface cursor-size "${"$"}{CURSOR_SIZE}" 2>/dev/null || true
                fi
                mkdir -p ${"$"}{HOME}/.config/gtk-3.0 2>/dev/null
                {
                    echo "[Settings]"
                    [ -n "${"$"}THEME" ] && echo "gtk-theme-name=${"$"}THEME"
                    [ -n "${"$"}ICON" ] && echo "gtk-icon-theme-name=${"$"}ICON"
                    [ -n "${"$"}CURSOR" ] && echo "gtk-cursor-theme-name=${"$"}CURSOR"
                    [ -n "${"$"}CURSOR_SIZE" ] && echo "gtk-cursor-theme-size=${"$"}CURSOR_SIZE"
                    echo "gtk-application-prefer-dark-theme=1"
                } > ${"$"}{HOME}/.config/gtk-3.0/settings.ini
            fi
            xfconf-query -c xsettings -m >/dev/null 2>&1
            sleep 1
        done
    ) >/dev/null 2>&1 &

    # Keep session alive
    wait ${'$'}SESSION_PID
""".trimIndent()
