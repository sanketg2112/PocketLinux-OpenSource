package com.sg.linuxgo.bootstrap

/** Part 4 of pocketlinux-launch shell. */
internal fun pocketLinuxLaunchScriptPart4(
    username: String,
    homeDir: String,
    selectedGuiMode: String,
    selectedDE: String,
    startCmd: String,
    gpuEnv: String
): String = """
        # ── Desktop ready marker for Android loading overlay ──
        # Host polls /tmp/pocketlinux-desktop-ready (hostTmpDir bind) and only
        # dismisses the fancy loading screen once the panel is up (or timeout).
        # MATE: also accept mate-session+marco (panel name can lag under PRoot).
        rm -f /tmp/pocketlinux-desktop-ready 2>/dev/null || true
        nohup sh -c '
            i=0
            _mate_panel_try=0
            while [ "${'$'}i" -lt 120 ]; do
                if command -v pgrep >/dev/null 2>&1; then
                    if pgrep -x xfce4-panel >/dev/null 2>&1; then
                        if pgrep -x xfdesktop >/dev/null 2>&1 \
                            && { pgrep -x xfwm4 >/dev/null 2>&1 || pgrep -x openbox >/dev/null 2>&1; }; then
                            sleep 2.0
                            echo xfce > /tmp/pocketlinux-desktop-ready
                            exit 0
                        fi
                    elif pgrep -x mate-panel >/dev/null 2>&1 \
                        || pgrep -f "[m]ate-panel" >/dev/null 2>&1 \
                        || pgrep -x lxqt-panel >/dev/null 2>&1 \
                        || pgrep -x plasmashell >/dev/null 2>&1 \
                        || pgrep -x gnome-shell >/dev/null 2>&1 \
                        || pgrep -x Hyprland >/dev/null 2>&1 \
                        || pgrep -x hyprland >/dev/null 2>&1 \
                        || pgrep -x waybar >/dev/null 2>&1 \
                        || pgrep -x quickshell >/dev/null 2>&1; then
                        sleep 0.5
                        echo panel > /tmp/pocketlinux-desktop-ready
                        exit 0
                    fi
                    # MATE: force-start panel once if session is up but panel is not
                    if [ "${'$'}_mate_panel_try" = "0" ] && [ "${'$'}i" -ge 20 ]; then
                        if pgrep -x mate-session >/dev/null 2>&1 || pgrep -f "[m]ate-session" >/dev/null 2>&1; then
                            _mate_panel_try=1
                            if command -v mate-panel >/dev/null 2>&1; then
                                echo "MATE: force-starting mate-panel" >> /tmp/pocketlinux-gui.log 2>/dev/null || true
                                DISPLAY="${'$'}DISPLAY" mate-panel >/dev/null 2>&1 &
                            fi
                            if command -v mate-settings-daemon >/dev/null 2>&1; then
                                DISPLAY="${'$'}DISPLAY" mate-settings-daemon >/dev/null 2>&1 &
                            fi
                        fi
                    fi
                    # MATE ready enough: session + window manager (panel may be delayed)
                    if [ "${'$'}i" -ge 35 ]; then
                        if pgrep -x mate-session >/dev/null 2>&1 || pgrep -f "[m]ate-session" >/dev/null 2>&1; then
                            if pgrep -x marco >/dev/null 2>&1 || pgrep -x openbox >/dev/null 2>&1 \
                                || pgrep -f "[m]arco" >/dev/null 2>&1; then
                                echo mate-session > /tmp/pocketlinux-desktop-ready
                                exit 0
                            fi
                        fi
                    fi
                else
                    if ps 2>/dev/null | grep -E "[x]fce4-panel|[m]ate-panel|[l]xqt-panel|[p]lasmashell|[m]ate-session|[H]yprland|[w]aybar|[q]uickshell" >/dev/null; then
                        sleep 0.5
                        echo panel > /tmp/pocketlinux-desktop-ready
                        exit 0
                    fi
                fi
                sleep 0.4
                i=${'$'}((i + 1))
            done
            echo timeout > /tmp/pocketlinux-desktop-ready
        ' >/dev/null 2>&1 &
        # Pass full identity into the DE. A bare `env DISPLAY=…` would drop
        # USER/HOME and XFCE would fall back to getpwuid → root under -0.
        # LXQt: if the session binary exits immediately, fall back to a manual
        # openbox + panel + desktop stack so the user is not stuck on black.
        if echo "$startCmd" | grep -q "startlxqt\|lxqt-session"; then
            # Refresh Environment identity only; keep user [General] prefs
            mkdir -p "${'$'}HOME/.config/lxqt" 2>/dev/null || true
            if type __pl_lxqt_env_upsert >/dev/null 2>&1; then
                __pl_lxqt_env_upsert "${'$'}HOME/.config/lxqt/session.conf"
            elif [ ! -f "${'$'}HOME/.config/lxqt/session.conf" ]; then
                cat > "${'$'}HOME/.config/lxqt/session.conf" << LXQTEOF
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
            fi
            # Resolve real binary at runtime (Kotlin bakes $startCmd as a literal)
            if command -v startlxqt >/dev/null 2>&1; then
                LXQT_BIN=startlxqt
            elif command -v lxqt-session >/dev/null 2>&1; then
                LXQT_BIN=lxqt-session
            else
                LXQT_BIN=""
            fi
            echo "LXQt launch binary: ${'$'}{LXQT_BIN:-NONE} as USER=${'$'}USER HOME=${'$'}HOME"
            # Shared identity env for session + fallback components
            __pl_lxqt_env() {
                env \
                    DISPLAY="${'$'}DISPLAY" \
                    DBUS_SESSION_BUS_ADDRESS="${'$'}DBUS_SESSION_BUS_ADDRESS" \
                    DBUS_SYSTEM_BUS_ADDRESS="${'$'}{DBUS_SYSTEM_BUS_ADDRESS-}" \
                    USER="${'$'}USER" \
                    LOGNAME="${'$'}LOGNAME" \
                    HOME="${'$'}HOME" \
                    PWD="${'$'}HOME" \
                    POCKETLINUX_USERNAME="${'$'}POCKETLINUX_USERNAME" \
                    XDG_CONFIG_HOME="${'$'}XDG_CONFIG_HOME" \
                    XDG_DATA_HOME="${'$'}XDG_DATA_HOME" \
                    XDG_CACHE_HOME="${'$'}XDG_CACHE_HOME" \
                    XDG_STATE_HOME="${'$'}XDG_STATE_HOME" \
                    XDG_RUNTIME_DIR="${'$'}XDG_RUNTIME_DIR" \
                    XDG_SESSION_TYPE=x11 \
                    XDG_CURRENT_DESKTOP=LXQt \
                    XDG_SESSION_DESKTOP=lxqt \
                    XDG_MENU_PREFIX=lxqt- \
                    QT_QPA_PLATFORM=xcb \
                    QT_XCB_GL_INTEGRATION=none \
                    QT_X11_NO_MITSHM=1 \
                    _X11_NO_MITSHM=1 \
                    QT_SCALE_FACTOR="${'$'}{QT_SCALE_FACTOR-}" \
                    PATH="${'$'}PATH" \
                    LANG="${'$'}{LANG:-C.UTF-8}" \
                    PULSE_RUNTIME_PATH="${'$'}{PULSE_RUNTIME_PATH-}" \
                    PULSE_SERVER="${'$'}{PULSE_SERVER-}" \
                    GDK_SCALE="${'$'}{GDK_SCALE-}" \
                    GDK_DPI_SCALE="${'$'}{GDK_DPI_SCALE-}" \
                    "${'$'}@"
            }
            if [ -n "${'$'}LXQT_BIN" ]; then
                __pl_lxqt_env ${'$'}LXQT_BIN &
                LXQT_PID=${'$'}!
                # Wait a few seconds; if session dies, start components manually
                _lx_wait=0
                while [ ${'$'}_lx_wait -lt 15 ]; do
                    if ! kill -0 ${'$'}LXQT_PID 2>/dev/null; then
                        echo "WARNING: LXQt session exited early — starting openbox fallback stack"
                        break
                    fi
                    # Session still alive and openbox present → good
                    if command -v pgrep >/dev/null 2>&1 && pgrep -x openbox >/dev/null 2>&1; then
                        echo "✓ LXQt: openbox is running (user=${'$'}USER)"
                        wait ${'$'}LXQT_PID
                        exit ${'$'}?
                    fi
                    sleep 0.4
                    _lx_wait=${'$'}((_lx_wait + 1))
                done
                if kill -0 ${'$'}LXQT_PID 2>/dev/null; then
                    # Session still running but openbox not detected yet — stay with it
                    wait ${'$'}LXQT_PID
                    exit ${'$'}?
                fi
            else
                echo "WARNING: no LXQt session binary found — using openbox fallback stack"
            fi
            # Fallback stack (same identity env)
            if command -v openbox >/dev/null 2>&1; then
                __pl_lxqt_env openbox &
                sleep 0.5
            fi
            if command -v pcmanfm-qt >/dev/null 2>&1; then
                __pl_lxqt_env pcmanfm-qt --desktop &
            fi
            if command -v lxqt-panel >/dev/null 2>&1; then
                __pl_lxqt_env lxqt-panel &
            fi
            if command -v lxqt-session >/dev/null 2>&1; then
                # Note: cannot `exec` a shell function in POSIX sh
                __pl_lxqt_env lxqt-session
                exit ${'$'}?
            fi
            # Keep container alive with openbox if nothing else
            wait
            exit 0
        fi
        # Final Arch override: never exec startxfce4 on Arch (session restore = OOM).
        # tawcroot: startxfce4 often exits ~5s (dbus/session teardown) and kills the
        # desktop — use mini-session keep-alive (xfwm4+panel+xfdesktop) instead.
        __PL_X11_CMD="$startCmd"
        if { [ -f /etc/arch-release ] || [ -f /etc/artix-release ]; } \
            && [ -x /usr/local/bin/pocketlinux-xfce-session ]; then
            case "${'$'}__PL_X11_CMD" in
                startxfce4*|*/startxfce4*|xfce4-session*|*/xfce4-session*)
                    __PL_X11_CMD=/usr/local/bin/pocketlinux-xfce-session
                    echo "Arch X11: using pocketlinux-xfce-session (blocked startxfce4 restore)"
                    ;;
            esac
        fi
        if [ "${'$'}{POCKETLINUX_RUNTIME:-}" = "tawcroot" ] \
            && [ -x /usr/local/bin/pocketlinux-xfce-session ]; then
            case "${'$'}__PL_X11_CMD" in
                startxfce4*|*/startxfce4*|xfce4-session*|*/xfce4-session*)
                    __PL_X11_CMD=/usr/local/bin/pocketlinux-xfce-session
                    echo "tawcroot X11: using pocketlinux-xfce-session (stable keep-alive)"
                    ;;
            esac
        fi
        # logout-watchdog-v2 (all distros): intentional Log Out only.
        # Do NOT treat panel crash as logout (that was a false-positive bug).
        # Intentional path: guest writes /tmp/pocketlinux-session-ended with
        # content "user_logout" (pocketlinux-logout / xfce4-session-logout only).
        # Host polls that marker and returns Home immediately.
        # Session process death (real DE exit) still ends proot so we never stick
        # on wallpaper forever — but only "user_logout" is claimed as Log Out.
        rm -f /tmp/pocketlinux-session-ended 2>/dev/null || true
        # MATE: native Marco — do NOT LD_PRELOAD gtk3-nocsd (can hang mate-panel under PRoot).
        # GIO volume mitigations without GIO_USE_VFS=local (keeps trash://).
        __PL_X11_EXTRA=""
        if echo "$startCmd" | grep -q "mate-session" || [ "$selectedDE" = "mate" ]; then
            export GTK_CSD=0
            unset GIO_USE_VFS 2>/dev/null || true
            unset GTK3_NOCSD_PRELOAD 2>/dev/null || true
            # Drop any earlier gtk3-nocsd preload so mate-panel can start
            if [ -n "${'$'}{LD_PRELOAD-}" ]; then
                __pl_new_lp=""
                old_IFS=${'$'}IFS
                IFS=:
                for __pl_p in ${'$'}LD_PRELOAD; do
                    case "${'$'}__pl_p" in
                        *gtk3-nocsd*) ;;
                        "") ;;
                        *) __pl_new_lp="${'$'}{__pl_new_lp:+${'$'}__pl_new_lp:}${'$'}__pl_p" ;;
                    esac
                done
                IFS=${'$'}old_IFS
                if [ -n "${'$'}__pl_new_lp" ]; then
                    export LD_PRELOAD="${'$'}__pl_new_lp"
                else
                    unset LD_PRELOAD
                fi
            fi
            __PL_X11_EXTRA="GTK_CSD=0 GIO_USE_VOLUME_MONITOR=unix GVFS_DISABLE_FUSE=1 GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1"
        else
            __PL_X11_EXTRA="GIO_USE_VOLUME_MONITOR=unix GVFS_DISABLE_FUSE=1 GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1 GIO_USE_VFS=local"
        fi
        # Pass GPU env explicitly — plain env inheritance is easy to lose under nested env -i.
        # CRITICAL: never pass GALLIUM_DRIVER= or ZINK_*= with empty values. Mesa treats
        # empty GALLIUM_DRIVER as "set", which breaks MESA_LOADER_DRIVER_OVERRIDE=kgsl
        # and glmark2 then reports "zink Vulkan …" even when Freedreno was selected.
        # Final Freedreno lock: lfdevs fat libgallium-*-devel.so + kgsl_dri entry.
        if [ "${'$'}{POCKETLINUX_GPU_MODE:-}" = "adreno_freedreno" ]; then
            unset GALLIUM_DRIVER
            unset ZINK_DESCRIPTORS
            unset ZINK_DEBUG
            _pl_jail=/usr/local/lib/pocketlinux-dri
            _pl_ok=0
            if [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] || [ -e /usr/lib/dri/kgsl_dri.so ]; then
                for _g in /usr/lib/aarch64-linux-gnu/libgallium*-devel.so /usr/lib/libgallium*-devel.so; do
                    [ -f "${'$'}_g" ] || continue
                    _sz=${'$'}(stat -c%s "${'$'}_g" 2>/dev/null || echo 0)
                    if [ "${'$'}_sz" -gt 10000000 ] 2>/dev/null; then _pl_ok=1; break; fi
                done
            fi
            if [ "${'$'}_pl_ok" = "1" ]; then
                _pl_kgsl=/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so
                [ -e "${'$'}_pl_kgsl" ] || _pl_kgsl=/usr/lib/dri/kgsl_dri.so
                mkdir -p "${'$'}_pl_jail" 2>/dev/null || true
                ln -sfn "${'$'}_pl_kgsl" "${'$'}_pl_jail/kgsl_dri.so" 2>/dev/null || true
                for _pl_mod in swrast kms_swrast; do
                    for _pl_dir in /usr/lib/dri /usr/lib/aarch64-linux-gnu/dri; do
                        if [ -e "${'$'}_pl_dir/${'$'}_pl_mod"_dri.so ]; then
                            ln -sfn "${'$'}_pl_dir/${'$'}_pl_mod"_dri.so "${'$'}_pl_jail/${'$'}_pl_mod"_dri.so 2>/dev/null || true
                            break
                        fi
                    done
                done
                rm -f "${'$'}_pl_jail/zink_dri.so" 2>/dev/null || true
                export LIBGL_DRIVERS_PATH="${'$'}_pl_jail:/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri"
                export MESA_LOADER_DRIVER_OVERRIDE=kgsl
                unset LIBGL_ALWAYS_SOFTWARE
                export TU_DEBUG=noconform
                export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
            else
                unset LIBGL_DRIVERS_PATH
                export LIBGL_ALWAYS_SOFTWARE=1
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=swrast
                export LIBGL_DRIVERS_PATH=/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri
            fi
        fi
        # shellcheck disable=SC2086
        if [ -n "${'$'}{LD_PRELOAD-}" ]; then
            __PL_ENV_LDPRELOAD="LD_PRELOAD=${'$'}LD_PRELOAD"
        else
            __PL_ENV_LDPRELOAD=""
        fi
        __PL_GPU_ENV=""
        [ -n "${'$'}{MESA_LOADER_DRIVER_OVERRIDE-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV MESA_LOADER_DRIVER_OVERRIDE=${'$'}MESA_LOADER_DRIVER_OVERRIDE"
        if [ "${'$'}{POCKETLINUX_GPU_MODE:-}" != "adreno_freedreno" ] && [ -n "${'$'}{GALLIUM_DRIVER-}" ]; then
            __PL_GPU_ENV="${'$'}__PL_GPU_ENV GALLIUM_DRIVER=${'$'}GALLIUM_DRIVER"
        fi
        [ -n "${'$'}{LIBGL_ALWAYS_SOFTWARE-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV LIBGL_ALWAYS_SOFTWARE=${'$'}LIBGL_ALWAYS_SOFTWARE"
        # Never leave a jail-only path (swrast missing there).
        case "${'$'}{LIBGL_DRIVERS_PATH:-}" in
            /usr/local/lib/pocketlinux-dri)
                if [ "${'$'}{MESA_LOADER_DRIVER_OVERRIDE:-}" = "kgsl" ]; then
                    export LIBGL_DRIVERS_PATH=/usr/local/lib/pocketlinux-dri:/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri
                else
                    export LIBGL_DRIVERS_PATH=/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri
                fi
                ;;
        esac
        [ -n "${'$'}{LIBGL_DRIVERS_PATH-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV LIBGL_DRIVERS_PATH=${'$'}LIBGL_DRIVERS_PATH"
        [ -n "${'$'}{TU_DEBUG-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV TU_DEBUG=${'$'}TU_DEBUG"
        [ -n "${'$'}{VK_ICD_FILENAMES-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV VK_ICD_FILENAMES=${'$'}VK_ICD_FILENAMES"
        [ -n "${'$'}{MESA_DEBUG-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV MESA_DEBUG=${'$'}MESA_DEBUG"
        [ -n "${'$'}{PROOT_L_MT-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV PROOT_L_MT=${'$'}PROOT_L_MT"
        [ -n "${'$'}{ZINK_DESCRIPTORS-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV ZINK_DESCRIPTORS=${'$'}ZINK_DESCRIPTORS"
        [ -n "${'$'}{ZINK_DEBUG-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV ZINK_DEBUG=${'$'}ZINK_DEBUG"
        [ -n "${'$'}{MESA_VK_WSI_PRESENT_MODE-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV MESA_VK_WSI_PRESENT_MODE=${'$'}MESA_VK_WSI_PRESENT_MODE"
        [ -n "${'$'}{MESA_SHADER_CACHE_DISABLE-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV MESA_SHADER_CACHE_DISABLE=${'$'}MESA_SHADER_CACHE_DISABLE"
        [ -n "${'$'}{MESA_SHADER_CACHE_MAX_SIZE-}" ] && __PL_GPU_ENV="${'$'}__PL_GPU_ENV MESA_SHADER_CACHE_MAX_SIZE=${'$'}MESA_SHADER_CACHE_MAX_SIZE"
        env \
            DISPLAY="${'$'}DISPLAY" \
            DBUS_SESSION_BUS_ADDRESS="${'$'}DBUS_SESSION_BUS_ADDRESS" \
            DBUS_SYSTEM_BUS_ADDRESS="${'$'}{DBUS_SYSTEM_BUS_ADDRESS-}" \
            USER="${'$'}USER" \
            LOGNAME="${'$'}LOGNAME" \
            HOME="${'$'}HOME" \
            PWD="${'$'}HOME" \
            POCKETLINUX_USERNAME="${'$'}POCKETLINUX_USERNAME" \
            XDG_CONFIG_HOME="${'$'}XDG_CONFIG_HOME" \
            XDG_DATA_HOME="${'$'}XDG_DATA_HOME" \
            XDG_CACHE_HOME="${'$'}XDG_CACHE_HOME" \
            XDG_STATE_HOME="${'$'}XDG_STATE_HOME" \
            XDG_RUNTIME_DIR="${'$'}XDG_RUNTIME_DIR" \
            XDG_SESSION_TYPE="${'$'}{XDG_SESSION_TYPE:-x11}" \
            XDG_CURRENT_DESKTOP="${'$'}{XDG_CURRENT_DESKTOP:-XFCE}" \
            XDG_SESSION_DESKTOP="${'$'}{XDG_SESSION_DESKTOP-}" \
            QT_QPA_PLATFORM="${'$'}{QT_QPA_PLATFORM-}" \
            QT_XCB_GL_INTEGRATION="${'$'}{QT_XCB_GL_INTEGRATION-}" \
            QT_X11_NO_MITSHM="${'$'}{QT_X11_NO_MITSHM-}" \
            PATH="${'$'}PATH" \
            LANG="${'$'}{LANG:-C.UTF-8}" \
            PULSE_RUNTIME_PATH="${'$'}{PULSE_RUNTIME_PATH-}" \
            PULSE_SERVER="${'$'}{PULSE_SERVER-}" \
            GDK_SCALE="${'$'}{GDK_SCALE-}" \
            GDK_DPI_SCALE="${'$'}{GDK_DPI_SCALE-}" \
            QT_SCALE_FACTOR="${'$'}{QT_SCALE_FACTOR-}" \
            LD_LIBRARY_PATH="${'$'}{LD_LIBRARY_PATH-}" \
            ${'$'}__PL_ENV_LDPRELOAD \
            ${'$'}__PL_GPU_ENV \
            GDK_GL=disable \
            ${'$'}__PL_X11_EXTRA \
            ${'$'}__PL_X11_CMD &
        SESSION_PID=${'$'}!
        echo "X11 session started pid=${'$'}SESSION_PID cmd=${'$'}__PL_X11_CMD gpu=${'$'}{MESA_LOADER_DRIVER_OVERRIDE:-none} gallium=${'$'}{GALLIUM_DRIVER:-unset} (logout-watchdog-v3)"
        # MATE: prefer native Marco (default MATE title bars; Appearance controls them).
        # Openbox only if Marco never starts (PRoot edge case).
        if echo "$startCmd" | grep -q "mate-session" || [ "$selectedDE" = "mate" ]; then
            nohup sh -c '
                DISPLAY="${'$'}DISPLAY"
                export DISPLAY HOME XDG_CONFIG_HOME DBUS_SESSION_BUS_ADDRESS
                export HOME="${'$'}HOME"
                export XDG_CONFIG_HOME="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
                export DBUS_SESSION_BUS_ADDRESS="${'$'}DBUS_SESSION_BUS_ADDRESS"
                export GTK_CSD=0
                _gset() {
                    command -v gsettings >/dev/null 2>&1 || return 0
                    gsettings set "${'$'}@" 2>/dev/null || true
                }
                _theme=TraditionalOk
                for _t in TraditionalOk Menta BlueMenta BlackMATE GreenLaguna Default; do
                    if [ -d "/usr/share/themes/${'$'}_t/metacity-1" ] || [ -d "/usr/share/themes/${'$'}_t/marco-1" ]; then
                        _theme="${'$'}_t"
                        break
                    fi
                done
                i=0
                while [ "${'$'}i" -lt 40 ]; do
                    if pgrep -x mate-session >/dev/null 2>&1 || pgrep -x mate-panel >/dev/null 2>&1; then
                        break
                    fi
                    i=${'$'}((i + 1))
                    sleep 0.35
                done
                # Safety only — do not reset user Marco/GTK theme or wallpaper here
                _gset org.mate.session.required-components windowmanager marco
                _gset org.mate.Marco.general compositing-manager false
                _gset org.mate.Marco.general mouse-button-modifier "<Alt>"
                _gset org.mate.interface gtk-dialogs-use-header false
                _cur=${'$'}(gsettings get org.mate.Marco.general theme 2>/dev/null || true)
                case "${'$'}_cur" in
                    *Adwaita*)
                        _gset org.mate.Marco.general theme "${'$'}_theme"
                        _gset org.mate.Marco.general button-layout "menu:minimize,maximize,close"
                        echo "MATE: fixed broken Marco theme → ${'$'}_theme"
                        ;;
                esac
                if [ -z "${'$'}_cur" ] || [ "${'$'}_cur" = "''" ] || [ "${'$'}_cur" = '""' ]; then
                    _gset org.mate.Marco.general theme "${'$'}_theme"
                    _gset org.mate.Marco.general button-layout "menu:minimize,maximize,close"
                fi
                # Stop openbox if we previously forced it — restore native MATE
                if pgrep -x openbox >/dev/null 2>&1; then
                    echo "MATE: replacing Openbox with native Marco"
                    pkill -x openbox 2>/dev/null || true
                    sleep 0.3
                fi
                if command -v marco >/dev/null 2>&1; then
                    if ! pgrep -x marco >/dev/null 2>&1 && ! pgrep -f "[m]arco" >/dev/null 2>&1; then
                        echo "MATE: starting marco --replace (native title bars)"
                        marco --replace >/dev/null 2>&1 &
                        sleep 1.2
                    else
                        echo "MATE: marco already running (user theme preserved)"
                    fi
                fi
                # Ensure panel is up so Android loading overlay can dismiss
                if ! pgrep -x mate-panel >/dev/null 2>&1 && ! pgrep -f "[m]ate-panel" >/dev/null 2>&1; then
                    if command -v mate-panel >/dev/null 2>&1; then
                        echo "MATE: starting mate-panel (was missing)"
                        mate-panel >/dev/null 2>&1 &
                        sleep 0.8
                    fi
                fi
                if ! pgrep -x mate-settings-daemon >/dev/null 2>&1 \
                    && ! pgrep -f "[m]ate-settings-daemon" >/dev/null 2>&1; then
                    if command -v mate-settings-daemon >/dev/null 2>&1; then
                        mate-settings-daemon >/dev/null 2>&1 &
                        sleep 0.5
                    fi
                fi
                # Wallpaper ASAP (do not wait for panel). User path only from gsettings.
                _paint_user_wp() {
                    _uwp=${'$'}(gsettings get org.mate.background picture-filename 2>/dev/null || true)
                    _uwp=${'$'}(printf '%s' "${'$'}_uwp" | sed "s/^'//;s/'$//")
                    if [ -n "${'$'}_uwp" ] && [ -f "${'$'}_uwp" ]; then
                        gsettings set org.mate.background draw-background true 2>/dev/null || true
                        gsettings set org.mate.background picture-filename "${'$'}_uwp" 2>/dev/null || true
                        if command -v feh >/dev/null 2>&1; then
                            feh --no-fehbg --bg-fill "${'$'}_uwp" 2>/dev/null || true
                        fi
                        echo "MATE: user wallpaper → ${'$'}_uwp"
                        return 0
                    fi
                    return 1
                }
                _paint_user_wp || echo "MATE: no user wallpaper in gsettings yet"
                if pgrep -x marco >/dev/null 2>&1 || pgrep -f "[m]arco" >/dev/null 2>&1; then
                    echo "MATE: marco OK — Appearance controls window borders"
                elif command -v openbox >/dev/null 2>&1; then
                    echo "WARNING: marco failed — Openbox fallback (PocketLinux-Dark)"
                    if [ -f "${'$'}HOME/.config/openbox/rc.xml" ]; then
                        sed -i "s/<name>[^<]*<\\/name>/<name>PocketLinux-Dark<\\/name>/g" \
                            "${'$'}HOME/.config/openbox/rc.xml" 2>/dev/null || true
                        openbox --config-file "${'$'}HOME/.config/openbox/rc.xml" --replace >/dev/null 2>&1 &
                    else
                        openbox --replace >/dev/null 2>&1 &
                    fi
                    sleep 1
                    openbox --reconfigure >/dev/null 2>&1 || true
                else
                    echo "WARNING: neither marco nor openbox available"
                fi
                # After WM may have cleared root — paint user wallpaper again immediately
                _paint_user_wp || true
                # Nudge ready marker if panel/session is alive
                if pgrep -x mate-panel >/dev/null 2>&1 || pgrep -f "[m]ate-panel" >/dev/null 2>&1 \
                    || pgrep -x mate-session >/dev/null 2>&1; then
                    echo panel > /tmp/pocketlinux-desktop-ready 2>/dev/null || true
                fi
                if command -v wmctrl >/dev/null 2>&1; then
                    sleep 1.0
                    wmctrl -l 2>/dev/null | while read -r id _rest; do
                        [ -n "${'$'}id" ] || continue
                        wmctrl -i -r "${'$'}id" -b remove,maximized_vert,maximized_horz 2>/dev/null || true
                    done
                    _paint_user_wp || true
                fi
            ' >/dev/null 2>&1 &
        fi
        __PL_USER_LOGOUT=0
        while kill -0 ${'$'}SESSION_PID 2>/dev/null; do
            if [ -f /tmp/pocketlinux-session-ended ]; then
                __pl_mk=${'$'}(cat /tmp/pocketlinux-session-ended 2>/dev/null || true)
                case "${'$'}__pl_mk" in
                    *user_logout*)
                        echo "logout-watchdog: intentional user_logout — ending session now"
                        __PL_USER_LOGOUT=1
                        break
                        ;;
                esac
            fi
            # Fast poll so Home appears quickly after confirmed Log Out
            sleep 0.2
        done
        if [ "${'$'}__PL_USER_LOGOUT" = "1" ]; then
            kill ${'$'}SESSION_PID 2>/dev/null || true
            sleep 0.15
            kill -9 ${'$'}SESSION_PID 2>/dev/null || true
            for __pl_p in xfce4-session startxfce4 xfce4-panel xfdesktop xfwm4 xfsettingsd \
                mate-session mate-panel lxqt-session lxqt-panel openbox plasmashell \
                gnome-session gnome-shell Hyprland hyprland weston waybar quickshell; do
                pkill -x "${'$'}__pl_p" 2>/dev/null || true
            done
            pkill -f '/usr/local/bin/pocketlinux-xfce-session' 2>/dev/null || true
            pkill -f 'startxfce4' 2>/dev/null || true
            echo "X11 intentional logout — proot exiting 0 (host Home)"
            exit 0
        fi
        # Session process ended on its own.
        wait ${'$'}SESSION_PID 2>/dev/null
        __PL_EC=${'$'}?
        # Marker may appear after DE exit (late write) — still Home
        if [ -f /tmp/pocketlinux-session-ended ]; then
            __pl_mk=${'$'}(cat /tmp/pocketlinux-session-ended 2>/dev/null || true)
            case "${'$'}__pl_mk" in
                *user_logout*)
                    echo "X11 session ended with user_logout marker — proot exiting 0 (host Home)"
                    exit 0
                    ;;
            esac
        fi
        # startxfce4 can exit while panel/wm still run (wrapper scripts, tawcroot).
        # Keep the guest alive while a desktop shell is still visible.
        __pl_desktop_alive() {
            pgrep -x xfce4-session >/dev/null 2>&1 \
                || pgrep -x mate-session >/dev/null 2>&1 \
                || pgrep -x lxqt-session >/dev/null 2>&1 \
                || pgrep -x gnome-session >/dev/null 2>&1 \
                || pgrep -x plasmashell >/dev/null 2>&1 \
                || pgrep -x xfce4-panel >/dev/null 2>&1 \
                || pgrep -x mate-panel >/dev/null 2>&1 \
                || pgrep -x lxqt-panel >/dev/null 2>&1 \
                || pgrep -x xfwm4 >/dev/null 2>&1 \
                || pgrep -x marco >/dev/null 2>&1 \
                || pgrep -x openbox >/dev/null 2>&1 \
                || pgrep -x Hyprland >/dev/null 2>&1 \
                || pgrep -x hyprland >/dev/null 2>&1 \
                || pgrep -x weston >/dev/null 2>&1 \
                || pgrep -x waybar >/dev/null 2>&1 \
                || pgrep -f '/usr/local/bin/pocketlinux-xfce-session' >/dev/null 2>&1
        }
        if __pl_desktop_alive; then
            echo "X11: session pid exited (ec=${'$'}__PL_EC) but desktop still up — keeping alive"
            while __pl_desktop_alive; do
                if [ -f /tmp/pocketlinux-session-ended ]; then
                    __pl_mk=${'$'}(cat /tmp/pocketlinux-session-ended 2>/dev/null || true)
                    case "${'$'}__pl_mk" in
                        *user_logout*)
                            echo "X11 intentional logout while keep-alive — proot exiting 0 (host Home)"
                            exit 0
                            ;;
                    esac
                fi
                sleep 0.4
            done
            echo "X11: desktop components exited — proot exiting 0 (host Home)"
            exit 0
        fi
        # Clean DE logout often leaves exit 0 without our marker (real binary).
        if [ "${'$'}__PL_EC" = "0" ]; then
            echo "X11 session exited 0 — proot exiting 0 (host Home)"
            exit 0
        fi
        # No session manager / panel left → treat as logout (avoids wallpaper-stuck sessions)
        if ! __pl_desktop_alive; then
            echo "X11 session managers gone (ec=${'$'}__PL_EC) — proot exiting 0 (host Home)"
            exit 0
        fi
        echo "X11 session process exited code=${'$'}__PL_EC (not intentional logout)"
        exit ${'$'}__PL_EC
    fi

    if [ "${'$'}GUI_MODE" = "wayland" ]; then
        export WAYLAND_DISPLAY=wayland-0
        export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp}"
        export XCURSOR_THEME=Adwaita
        export XCURSOR_SIZE=12
        # Host PulseAudio (native-protocol-tcp + AAudio) — keep for session children
        export PULSE_SERVER=tcp:127.0.0.1:14713
        # Desktop ready marker (same as X11 — host may poll rootfs /tmp)
        rm -f /tmp/pocketlinux-desktop-ready 2>/dev/null || true
        nohup sh -c '
            i=0
            while [ "${'$'}i" -lt 180 ]; do
                if command -v pgrep >/dev/null 2>&1; then
                    if pgrep -x xfce4-panel >/dev/null 2>&1 \
                        || pgrep -x mate-panel >/dev/null 2>&1 \
                        || pgrep -x lxqt-panel >/dev/null 2>&1 \
                        || pgrep -x plasmashell >/dev/null 2>&1 \
                        || pgrep -x Hyprland >/dev/null 2>&1 \
                        || pgrep -x hyprland >/dev/null 2>&1 \
                        || pgrep -x waybar >/dev/null 2>&1 \
                        || pgrep -x quickshell >/dev/null 2>&1; then
                        sleep 0.7
                        echo panel > /tmp/pocketlinux-desktop-ready
                        exit 0
                    fi
                fi
                sleep 0.4
                i=${'$'}((i + 1))
            done
            echo timeout > /tmp/pocketlinux-desktop-ready
        ' >/dev/null 2>&1 &

        # Check which session command to run
        if echo "$startCmd" | grep -q "xfce4"; then
            export XDG_CURRENT_DESKTOP=XFCE
            # Local Desktop model: nested labwc via startxfce4 --wayland against host wayland-0
            if ! command -v labwc >/dev/null 2>&1; then
                echo "FATAL: labwc not found. Arch/XFCE Wayland requires labwc (pacman -S labwc)."
                exit 1
            fi
            if ! command -v startxfce4 >/dev/null 2>&1; then
                echo "FATAL: startxfce4 not found. Install xfce4 (pacman -S xfce4 xfce4-session)."
                exit 1
            fi
            exec dbus-run-session startxfce4 --wayland 2>&1
        elif echo "$startCmd" | grep -q "startplasma"; then
            export XDG_CURRENT_DESKTOP=KDE
            if command -v dbus-run-session >/dev/null 2>&1; then
                exec dbus-run-session -- $startCmd 2>&1
            else
                exec $startCmd 2>&1
            fi
        elif echo "$startCmd" | grep -q "mate-session"; then
            export XDG_CURRENT_DESKTOP=MATE
            if command -v dbus-run-session >/dev/null 2>&1; then
                exec dbus-run-session -- $startCmd 2>&1
            else
                exec $startCmd 2>&1
            fi
        elif echo "$startCmd" | grep -q "startlxqt\|lxqt-session"; then
            export XDG_CURRENT_DESKTOP=LXQt
            if command -v dbus-run-session >/dev/null 2>&1; then
                exec dbus-run-session -- $startCmd 2>&1
            else
                exec $startCmd 2>&1
            fi
        else
            # Fallback for other DEs/WMs: just run startCmd directly on wayland-0
            if command -v dbus-run-session >/dev/null 2>&1; then
                exec dbus-run-session -- $startCmd 2>&1
            else
                exec $startCmd 2>&1
            fi
        fi
    fi

    # VNC fallback is no longer supported
    echo "FATAL: VNC mode is deprecated and removed."
    exit 1
""".trimIndent()
