package com.sg.linuxgo.bootstrap

/** Part 3 of pocketlinux-launch shell. */
internal fun pocketLinuxLaunchScriptPart3(
    username: String,
    homeDir: String,
    selectedGuiMode: String,
    selectedDE: String,
    startCmd: String,
    gpuEnv: String
): String = """
            export GTK_CSD=0
            mkdir -p "${'$'}HOME/.config/gtk-3.0" 2>/dev/null || true
            # Seed gtk-3.0 settings once only
            if [ ! -f "${'$'}HOME/.config/gtk-3.0/settings.ini" ]; then
                cat > "${'$'}HOME/.config/gtk-3.0/settings.ini" << 'GTK3EOF'
[Settings]
gtk-theme-name=Adwaita-dark
gtk-icon-theme-name=Tela
gtk-application-prefer-dark-theme=1
gtk-dialogs-use-header=0
gtk-decoration-layout=menu:minimize,maximize,close
GTK3EOF
            fi
            __pl_mate_gset() {
                if command -v gsettings >/dev/null 2>&1; then
                    if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                        gsettings set "${'$'}@" 2>/dev/null || true
                    elif command -v dbus-run-session >/dev/null 2>&1; then
                        dbus-run-session -- gsettings set "${'$'}@" 2>/dev/null || true
                    fi
                fi
            }
            __pl_mate_gget() {
                if command -v gsettings >/dev/null 2>&1; then
                    if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                        gsettings get "${'$'}@" 2>/dev/null || true
                    elif command -v dbus-run-session >/dev/null 2>&1; then
                        dbus-run-session -- gsettings get "${'$'}@" 2>/dev/null || true
                    fi
                fi
            }
            # ── Every boot: PRoot safety only (never theme / wallpaper / gtk theme) ──
            __pl_mate_gset org.mate.session.required-components windowmanager marco
            __pl_mate_gset org.mate.Marco.general compositing-manager false
            __pl_mate_gset org.mate.Marco.general mouse-button-modifier '<Alt>'
            __pl_mate_gset org.mate.interface gtk-dialogs-use-header false
            # Repair only if Marco theme is still the broken GTK Adwaita value
            _cur_marco=${'$'}(__pl_mate_gget org.mate.Marco.general theme)
            case "${'$'}_cur_marco" in
                *Adwaita*)
                    __pl_mate_gset org.mate.Marco.general theme "${'$'}_marco_theme"
                    __pl_mate_gset org.mate.Marco.general button-layout 'menu:minimize,maximize,close'
                    echo "MATE: repaired broken Marco theme (${'$'}_cur_marco → ${'$'}_marco_theme)"
                    ;;
            esac
            # Also treat empty / quoted-empty as broken (avoid fragile case |"" patterns on ash)
            if [ -z "${'$'}_cur_marco" ] || [ "${'$'}_cur_marco" = "''" ] || [ "${'$'}_cur_marco" = '""' ]; then
                __pl_mate_gset org.mate.Marco.general theme "${'$'}_marco_theme"
                __pl_mate_gset org.mate.Marco.general button-layout 'menu:minimize,maximize,close'
            fi
            # ── Never gsettings-set wallpaper or gtk-theme after install ──
            # One-shot: mark container so we do not treat every boot as "empty dconf".
            if [ ! -f "${'$'}_mate_seed" ] && [ ! -f "${'$'}_mate_seed_sys" ]; then
                # Safety layout only (not cosmetics users change in Appearance)
                __pl_mate_gset org.mate.Marco.general button-layout 'menu:minimize,maximize,close'
                __pl_mate_gset org.mate.Marco.general center-new-windows true
                __pl_mate_gset org.mate.Marco.general raise-on-click true
                __pl_mate_gset org.mate.Marco.general focus-mode 'click'
                __pl_mate_gset org.mate.interface gtk-decoration-layout 'menu:minimize,maximize,close'
                _exist_marco=${'$'}(__pl_mate_gget org.mate.Marco.general theme)
                case "${'$'}_exist_marco" in
                    *Adwaita*)
                        __pl_mate_gset org.mate.Marco.general theme "${'$'}_marco_theme"
                        echo "MATE: one-shot repair Marco theme Adwaita → ${'$'}_marco_theme"
                        ;;
                esac
                # Do NOT set picture-filename / gtk-theme — leave stock MATE or user picks
                mkdir -p "${'$'}HOME/.config/mate" /var/lib/pocketlinux 2>/dev/null || true
                touch "${'$'}_mate_seed" "${'$'}_mate_seed_sys" 2>/dev/null || true
                echo "MATE: marked prefs as user-owned (no wallpaper/theme force on later boots)"
            else
                mkdir -p "${'$'}HOME/.config/mate" /var/lib/pocketlinux 2>/dev/null || true
                touch "${'$'}_mate_seed" "${'$'}_mate_seed_sys" 2>/dev/null || true
                echo "MATE: keeping user theme/wallpaper (seed marker present)"
            fi
            echo "MATE: native Marco (Appearance/wallpaper not rewritten by launch)"
            # Openbox is ONLY a last-resort fallback if Marco cannot decorate under PRoot.
            # Install a dark Openbox theme so fallback is not Clearlooks blue.
            __pl_install_ob_dark() {
                _themerc_body='
# PocketLinux-Dark — matches Adwaita-dark; no Clearlooks blue
padding.width: 6
padding.height: 4
border.width: 1
window.handle.width: 0
window.client.padding.width: 0
window.client.padding.height: 0
window.label.text.justify: Left

window.active.border.color: #1a1a1a
window.inactive.border.color: #1a1a1a
window.active.title.separator.color: #1a1a1a
window.inactive.title.separator.color: #1a1a1a
window.active.client.color: #1a1a1a
window.inactive.client.color: #1a1a1a

window.active.title.bg: flat solid
window.active.title.bg.color: #2d2d2d
window.active.label.bg: parentrelative
window.active.label.text.color: #eeeeee
window.active.label.text.font: shadow=n
window.active.handle.bg: flat solid
window.active.handle.bg.color: #2d2d2d
window.active.grip.bg: parentrelative

window.active.button.unpressed.bg: parentrelative
window.active.button.unpressed.image.color: #c8c8c8
window.active.button.pressed.bg: flat solid
window.active.button.pressed.bg.color: #444444
window.active.button.pressed.image.color: #ffffff
window.active.button.hover.bg: flat solid
window.active.button.hover.bg.color: #3a3a3a
window.active.button.hover.image.color: #ffffff
window.active.button.disabled.bg: parentrelative
window.active.button.disabled.image.color: #666666
window.active.button.toggled.unpressed.bg: parentrelative
window.active.button.toggled.unpressed.image.color: #e0e0e0
window.active.button.toggled.pressed.bg: flat solid
window.active.button.toggled.pressed.bg.color: #444444
window.active.button.toggled.pressed.image.color: #ffffff
window.active.button.toggled.hover.bg: flat solid
window.active.button.toggled.hover.bg.color: #3a3a3a
window.active.button.toggled.hover.image.color: #ffffff

window.inactive.title.bg: flat solid
window.inactive.title.bg.color: #242424
window.inactive.label.bg: parentrelative
window.inactive.label.text.color: #888888
window.inactive.label.text.font: shadow=n
window.inactive.handle.bg: flat solid
window.inactive.handle.bg.color: #242424
window.inactive.grip.bg: parentrelative
window.inactive.button.unpressed.bg: parentrelative
window.inactive.button.unpressed.image.color: #666666
window.inactive.button.pressed.bg: flat solid
window.inactive.button.pressed.bg.color: #333333
window.inactive.button.pressed.image.color: #bbbbbb
window.inactive.button.hover.bg: flat solid
window.inactive.button.hover.bg.color: #303030
window.inactive.button.hover.image.color: #cccccc
window.inactive.button.disabled.bg: parentrelative
window.inactive.button.disabled.image.color: #444444
window.inactive.button.toggled.unpressed.bg: parentrelative
window.inactive.button.toggled.unpressed.image.color: #777777
window.inactive.button.toggled.pressed.bg: flat solid
window.inactive.button.toggled.pressed.bg.color: #333333
window.inactive.button.toggled.pressed.image.color: #bbbbbb
window.inactive.button.toggled.hover.bg: flat solid
window.inactive.button.toggled.hover.bg.color: #303030
window.inactive.button.toggled.hover.image.color: #cccccc

menu.border.width: 1
menu.border.color: #1a1a1a
menu.title.bg: flat solid
menu.title.bg.color: #2d2d2d
menu.title.text.color: #eeeeee
menu.title.text.justify: Left
menu.items.bg: flat solid
menu.items.bg.color: #2d2d2d
menu.items.text.color: #e0e0e0
menu.items.disabled.text.color: #666666
menu.items.active.bg: flat solid
menu.items.active.bg.color: #3584e4
menu.items.active.text.color: #ffffff
menu.separator.color: #3a3a3a
menu.separator.width: 1
menu.separator.padding.width: 4
menu.separator.padding.height: 3

osd.border.width: 1
osd.border.color: #1a1a1a
osd.bg: flat solid
osd.bg.color: #2d2d2d
osd.label.bg: parentrelative
osd.label.text.color: #eeeeee
'
                for _base in \
                    "${'$'}HOME/.themes" \
                    "${'$'}HOME/.local/share/themes" \
                    /usr/share/themes \
                    /etc/xdg/openbox/themes; do
                    _td="${'$'}_base/PocketLinux-Dark/openbox-3"
                    mkdir -p "${'$'}_td" 2>/dev/null || true
                    printf '%s\n' "${'$'}_themerc_body" > "${'$'}_td/themerc" 2>/dev/null || true
                done
                mkdir -p "${'$'}HOME/.config/openbox" 2>/dev/null || true
                cat > "${'$'}HOME/.config/openbox/rc.xml" << 'OBMATE'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc"
  xmlns:xi="http://www.w3.org/2001/XInclude">
  <theme>
    <name>PocketLinux-Dark</name>
    <titleLayout>LIMC</titleLayout>
    <keepBorder>yes</keepBorder>
    <animateIconify>no</animateIconify>
    <font place="ActiveWindow">
      <name>Sans</name>
      <size>10</size>
      <weight>Bold</weight>
      <slant>Normal</slant>
    </font>
    <font place="InactiveWindow">
      <name>Sans</name>
      <size>10</size>
      <weight>Normal</weight>
      <slant>Normal</slant>
    </font>
  </theme>
  <mouse>
    <dragThreshold>3</dragThreshold>
    <doubleClickTime>300</doubleClickTime>
    <context name="Titlebar">
      <mousebind button="Left" action="Drag">
        <action name="Move"/>
      </mousebind>
      <mousebind button="Left" action="DoubleClick">
        <action name="ToggleMaximize"/>
      </mousebind>
    </context>
    <context name="Frame">
      <mousebind button="A-Left" action="Drag">
        <action name="Move"/>
      </mousebind>
      <mousebind button="A-Right" action="Drag">
        <action name="Resize"/>
      </mousebind>
    </context>
  </mouse>
  <focus>
    <focusNew>yes</focusNew>
    <followMouse>no</followMouse>
    <raiseOnFocus>no</raiseOnFocus>
  </focus>
  <placement>
    <policy>Smart</policy>
    <center>yes</center>
  </placement>
</openbox_config>
OBMATE
                # Nuke any leftover Clearlooks reference
                sed -i 's/<name>Clearlooks<\/name>/<name>PocketLinux-Dark<\/name>/g' \
                    "${'$'}HOME/.config/openbox/rc.xml" 2>/dev/null || true
                # System helper so Appearance / restarts can re-apply without blue bars
                if [ "${'$'}(id -u)" = "0" ] || [ -w /usr/local/bin ]; then
                    cat > /usr/local/bin/pocketlinux-openbox-theme << 'OBTH'
#!/bin/sh
# Re-apply PocketLinux-Dark Openbox theme (MATE Appearance does not change Openbox).
export HOME="${'$'}{HOME:-/root}"
OBRC="${'$'}HOME/.config/openbox/rc.xml"
if [ -f "${'$'}OBRC" ]; then
    sed -i 's/<name>[^<]*<\/name>/<name>PocketLinux-Dark<\/name>/' "${'$'}OBRC" 2>/dev/null || true
fi
if command -v openbox >/dev/null 2>&1; then
    if pgrep -x openbox >/dev/null 2>&1; then
        openbox --reconfigure 2>/dev/null || true
    fi
fi
exit 0
OBTH
                    chmod 755 /usr/local/bin/pocketlinux-openbox-theme 2>/dev/null || true
                fi
                echo "MATE: Openbox fallback theme ready (only if Marco fails)"
            }
            __pl_install_ob_dark
            # Native MATE: Marco is the window manager (Appearance controls its title bars).
            if type __pl_mate_gset >/dev/null 2>&1; then
                __pl_mate_gset org.mate.session.required-components windowmanager marco
            elif command -v gsettings >/dev/null 2>&1; then
                if [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                    gsettings set org.mate.session.required-components windowmanager marco 2>/dev/null || true
                elif command -v dbus-run-session >/dev/null 2>&1; then
                    dbus-run-session -- gsettings set org.mate.session.required-components windowmanager marco 2>/dev/null || true
                fi
            fi
            # Appearance wrapper: GIO safety only (Marco theme follows Appearance).
            for _app in mate-appearance-properties mate-control-center; do
                if [ ! -x "/usr/bin/${'$'}_app" ]; then
                    continue
                fi
                if [ ! -x "/usr/bin/${'$'}_app.real" ]; then
                    if head -1 "/usr/bin/${'$'}_app" 2>/dev/null | grep -q '^#!'; then
                        grep -q 'PocketLinux' "/usr/bin/${'$'}_app" 2>/dev/null || continue
                    else
                        mv "/usr/bin/${'$'}_app" "/usr/bin/${'$'}_app.real" 2>/dev/null || true
                    fi
                fi
                if [ -x "/usr/bin/${'$'}_app.real" ]; then
                    printf '%s\n' \
                        '#!/bin/sh' \
                        '# PocketLinux mate appearance wrapper' \
                        'export GIO_USE_VOLUME_MONITOR=unix' \
                        'export GVFS_DISABLE_FUSE=1' \
                        'export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1' \
                        'unset GIO_USE_VFS' \
                        'export GDK_GL=disable' \
                        "export GTK_CSD=0" \
                        "exec /usr/bin/${'$'}_app.real \"${'$'}@\"" \
                        > "/usr/bin/${'$'}_app"
                    chmod 755 "/usr/bin/${'$'}_app" 2>/dev/null || true
                fi
            done
            # Do not LD_PRELOAD gtk3-nocsd for the whole MATE session — it can prevent
            # mate-panel from starting under PRoot (stuck at "waiting for panel").
            unset GTK3_NOCSD_PRELOAD 2>/dev/null || true
            export GTK_CSD=0
            # ── Trash + desktop wallpaper UX under PRoot ──
            # 1) Standard XDG trash dirs (gvfsd-trash uses these)
            mkdir -p "${'$'}HOME/.local/share/Trash/files" \
                     "${'$'}HOME/.local/share/Trash/info" \
                     "${'$'}HOME/Desktop" \
                     "${'$'}HOME/.config" 2>/dev/null || true
            chmod 700 "${'$'}HOME/.local/share/Trash" 2>/dev/null || true
            # 2) Prefer Caja for folders/trash (legacy mimeapps often pinned Thunar,
            #    which is missing on pure MATE → "Trash is not available")
            _caja_desk=caja.desktop
            for _cd in /usr/share/applications/caja.desktop \
                       /usr/share/applications/org.mate.caja.desktop \
                       /usr/share/applications/caja-folder-handler.desktop; do
                if [ -f "${'$'}_cd" ]; then
                    _caja_desk=${'$'}(basename "${'$'}_cd")
                    break
                fi
            done
            _mime="${'$'}HOME/.config/mimeapps.list"
            if [ ! -f "${'$'}_mime" ]; then
                printf '%s\n' '[Default Applications]' \
                    "inode/directory=${'$'}_caja_desk" \
                    "inode/mount-point=${'$'}_caja_desk" \
                    "x-scheme-handler/trash=${'$'}_caja_desk" > "${'$'}_mime"
            else
                # Rewrite trash/dir handlers if they still point at missing thunar
                if grep -q 'thunar\|Thunar' "${'$'}_mime" 2>/dev/null \
                    || ! grep -q '^x-scheme-handler/trash=' "${'$'}_mime" 2>/dev/null; then
                    if grep -q '\[Default Applications\]' "${'$'}_mime" 2>/dev/null; then
                        sed -i \
                            -e "s|^inode/directory=.*|inode/directory=${'$'}_caja_desk|" \
                            -e "s|^inode/mount-point=.*|inode/mount-point=${'$'}_caja_desk|" \
                            -e "s|^x-scheme-handler/trash=.*|x-scheme-handler/trash=${'$'}_caja_desk|" \
                            "${'$'}_mime" 2>/dev/null || true
                        grep -q '^inode/directory=' "${'$'}_mime" 2>/dev/null \
                            || sed -i "/\[Default Applications\]/a inode/directory=${'$'}_caja_desk" "${'$'}_mime" 2>/dev/null || true
                        grep -q '^x-scheme-handler/trash=' "${'$'}_mime" 2>/dev/null \
                            || sed -i "/\[Default Applications\]/a x-scheme-handler/trash=${'$'}_caja_desk" "${'$'}_mime" 2>/dev/null || true
                    else
                        printf '\n[Default Applications]\ninode/directory=%s\ninode/mount-point=%s\nx-scheme-handler/trash=%s\n' \
                            "${'$'}_caja_desk" "${'$'}_caja_desk" "${'$'}_caja_desk" >> "${'$'}_mime"
                    fi
                fi
            fi
            # System mimeapps often still say thunar from a shared install template
            if [ -f /etc/xdg/mimeapps.list ] && grep -q 'thunar\|Thunar' /etc/xdg/mimeapps.list 2>/dev/null; then
                sed -i \
                    -e "s|^inode/directory=.*|inode/directory=${'$'}_caja_desk|" \
                    -e "s|^inode/mount-point=.*|inode/mount-point=${'$'}_caja_desk|" \
                    -e "s|^x-scheme-handler/trash=.*|x-scheme-handler/trash=${'$'}_caja_desk|" \
                    /etc/xdg/mimeapps.list 2>/dev/null || true
            fi
            # 3) Soft-start gvfsd so trash:// works (ignore failures under PRoot)
            if ! pgrep -x gvfsd >/dev/null 2>&1; then
                for _gvfsd in /usr/libexec/gvfsd /usr/lib/gvfs/gvfsd \
                    /usr/lib/aarch64-linux-gnu/gvfs/gvfsd; do
                    if [ -x "${'$'}_gvfsd" ]; then
                        "${'$'}_gvfsd" >/dev/null 2>&1 &
                        break
                    fi
                done
            fi
            # 4) Wallpaper / "Change Desktop Background" — ensure UI + image paths exist
            mkdir -p /usr/share/backgrounds /usr/share/backgrounds/mate 2>/dev/null || true
            if [ -n "${'$'}_mate_wp" ] && [ -f "${'$'}_mate_wp" ]; then
                cp -f "${'$'}_mate_wp" /usr/share/backgrounds/mate/pocketlinux_wp.png 2>/dev/null || true
            fi
            # Safe wrappers: avoid udisks hangs in GtkFileChooser, but keep trash VFS.
            # mate-appearance-properties is wrapped earlier (re-applies Openbox dark theme).
            for _app in mate-about-me; do
                if [ -x "/usr/bin/${'$'}_app" ] && ! grep -q 'PocketLinux' "/usr/bin/${'$'}_app" 2>/dev/null; then
                    if [ ! -x "/usr/bin/${'$'}_app.real" ]; then
                        mv "/usr/bin/${'$'}_app" "/usr/bin/${'$'}_app.real" 2>/dev/null || true
                    fi
                    if [ -x "/usr/bin/${'$'}_app.real" ]; then
                        printf '%s\n' \
                            '#!/bin/sh' \
                            '# PocketLinux mate GIO wrapper' \
                            'export GIO_USE_VOLUME_MONITOR=unix' \
                            'export GVFS_DISABLE_FUSE=1' \
                            'export GVFS_REMOTE_VOLUME_MONITOR_IGNORE=1' \
                            'unset GIO_USE_VFS' \
                            'export GDK_GL=disable' \
                            "exec /usr/bin/${'$'}_app.real \"\$@\"" \
                            > "/usr/bin/${'$'}_app"
                        chmod 755 "/usr/bin/${'$'}_app" 2>/dev/null || true
                    fi
                fi
            done
            # One-shot apt install of gvfs if trash backend missing (background)
            if ! command -v gio >/dev/null 2>&1 \
                && [ ! -f /var/lib/pocketlinux/mate_gvfs_try_v1 ] \
                && command -v apt-get >/dev/null 2>&1; then
                mkdir -p /var/lib/pocketlinux 2>/dev/null || true
                touch /var/lib/pocketlinux/mate_gvfs_try_v1 2>/dev/null || true
                nohup sh -c 'export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends gvfs gvfs-backends gvfs-daemons mate-control-center 2>/dev/null; echo mate-gvfs-done' >/dev/null 2>&1 &
                echo "MATE: installing gvfs + mate-control-center in background (trash/wallpaper)"
            fi
            echo "MATE: trash dirs + Caja mime handlers ready (handler=${'$'}_caja_desk)"
        fi
        # Keep re-painting until desktop owns root (WM blanks root on start).
        # MATE / tawcroot mini-session: never re-feh after DE up — overwrites user wallpapers.
        if [ "${'$'}__PL_IS_MATE" != "1" ] && [ "${'$'}{POCKETLINUX_RUNTIME:-}" != "tawcroot" ] \
            && [ "${'$'}{POCKETLINUX_MINI_SESSION:-}" != "1" ]; then
            WP_FOR_LOOP="${'$'}WP"
            # shell $ vars must be ${'$'}… so Kotlin does not interpolate them
            nohup sh -c '
                WP="${'$'}0"
                set_wp() {
                    if [ -n "${'$'}WP" ] && command -v feh >/dev/null 2>&1; then
                        feh --no-fehbg --bg-fill "${'$'}WP" 2>/dev/null || feh --bg-fill "${'$'}WP" 2>/dev/null || true
                        return 0
                    fi
                    if [ -n "${'$'}WP" ] && command -v xwallpaper >/dev/null 2>&1; then
                        xwallpaper --zoom "${'$'}WP" 2>/dev/null || true
                        return 0
                    fi
                    command -v xsetroot >/dev/null 2>&1 && xsetroot -solid "#000000" 2>/dev/null || true
                    return 1
                }
                w=0
                while [ "${'$'}w" -lt 20 ]; do
                    sleep 0.75
                    set_wp
                    if command -v pgrep >/dev/null 2>&1; then
                        if pgrep -x xfdesktop >/dev/null 2>&1; then
                            sleep 1
                            set_wp
                            break
                        fi
                    fi
                    w=${'$'}((w + 1))
                done
            ' "${'$'}WP_FOR_LOOP" >/dev/null 2>&1 &
        fi
        # Desktop backdrop defaults: create only when missing.
        # Never rewrite an existing xfce4-desktop.xml — that wiped wallpapers,
        # desktop icons, and other user backdrop prefs on every GUI start.
        mkdir -p ${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml 2>/dev/null
        if [ ! -f ${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml ]; then
            WP_VAL=""
            IMG_STYLE="0"
            if [ -n "${'$'}WP" ]; then
                WP_VAL="${'$'}WP"
                IMG_STYLE="5"
            fi
            cat > ${'$'}HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml << XDD
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
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
      <property name="monitor1" type="empty">
<property name="workspace0" type="empty">
  <property name="color-style" type="int" value="0"/>
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
      <property name="monitorVirtual-0" type="empty">
<property name="workspace0" type="empty">
  <property name="color-style" type="int" value="0"/>
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
      <property name="monitorVirtual-1" type="empty">
<property name="workspace0" type="empty">
  <property name="color-style" type="int" value="0"/>
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
      <property name="monitorXWAYLAND0" type="empty">
<property name="workspace0" type="empty">
  <property name="color-style" type="int" value="0"/>
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
      <property name="monitorXWAYLAND1" type="empty">
<property name="workspace0" type="empty">
  <property name="color-style" type="int" value="0"/>
  <property name="image-style" type="int" value="${'$'}IMG_STYLE"/>
  <property name="last-image" type="string" value="${'$'}WP_VAL"/>
</property>
      </property>
    </property>
  </property>
</channel>
XDD
        fi

        # ── Final identity re-assert right before DE (GUI must not be root) ──
        if [ -r /etc/pocketlinux/username ]; then
            __pl_f=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
            if [ -n "${'$'}__pl_f" ] && [ "${'$'}__pl_f" != "root" ]; then
                export POCKETLINUX_USERNAME="${'$'}__pl_f"
            fi
            unset __pl_f
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
        if [ -n "${'$'}POCKETLINUX_USERNAME" ] && [ "${'$'}POCKETLINUX_USERNAME" != "root" ]; then
            export USER="${'$'}POCKETLINUX_USERNAME" LOGNAME="${'$'}POCKETLINUX_USERNAME"
            export HOME="/home/${'$'}POCKETLINUX_USERNAME"
            export PWD="${'$'}HOME"
            export XDG_CONFIG_HOME="${'$'}HOME/.config"
            export XDG_DATA_HOME="${'$'}HOME/.local/share"
            export XDG_CACHE_HOME="${'$'}HOME/.cache"
            export XDG_STATE_HOME="${'$'}HOME/.local/state"
            mkdir -p "${'$'}HOME" "${'$'}XDG_CONFIG_HOME" "${'$'}XDG_DATA_HOME" "${'$'}XDG_CACHE_HOME" "${'$'}XDG_STATE_HOME" 2>/dev/null || true
            cd "${'$'}HOME" 2>/dev/null || true
            # Persist for next boot / nested tools
            mkdir -p /etc/pocketlinux 2>/dev/null || true
            echo "${'$'}POCKETLINUX_USERNAME" > /etc/pocketlinux/username 2>/dev/null || true
        fi
        # Never leave system bus unset — empty env makes GLib use a missing default path.
        if [ -z "${'$'}DBUS_SYSTEM_BUS_ADDRESS" ]; then
            if [ -S /run/dbus/system_bus_socket ] || [ -e /run/dbus/system_bus_socket ]; then
                export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
            elif [ -S /tmp/pl-system-bus ]; then
                export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/tmp/pl-system-bus
            elif [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                export DBUS_SYSTEM_BUS_ADDRESS=${'$'}DBUS_SESSION_BUS_ADDRESS
            fi
        fi
        echo "Starting $startCmd on ${'$'}DISPLAY as ${'$'}USER (HOME=${'$'}HOME, DESKTOP=${'$'}XDG_CURRENT_DESKTOP) dbus_sys=${'$'}{DBUS_SYSTEM_BUS_ADDRESS:-NONE} ..."
        # PolicyKit agents cannot register under PRoot and show an empty auth popup
        # (Void xfce4 meta pulls xfce-polkit; Debian/Arch may pull the GNOME agent).
        pkill -9 -x xfce-polkit 2>/dev/null || true
        pkill -9 -x polkit-gnome-authentication-agent-1 2>/dev/null || true
        # Arch+XFCE: repair broken Thunar capital-T wrappers (exit path → .real missing)
        # that spam the log and can cascade into session instability under PRoot.
        if [ -f /etc/arch-release ]; then
            # Wipe session restore only (do not kill user browsers on relaunch)
            rm -rf "${'$'}HOME/.cache/sessions" "${'$'}HOME/.cache/xfce4/sessions" 2>/dev/null || true
            mkdir -p "${'$'}HOME/.cache/sessions" 2>/dev/null || true
            rm -f /tmp/pocketlinux-block-heavy 2>/dev/null || true
            pkill -9 -x polkit-gnome-authentication-agent-1 2>/dev/null || true
            # Remove broken capital-T Thunar wrappers
            for _th in /usr/sbin/Thunar /usr/sbin/thunar /usr/local/bin/Thunar /usr/local/bin/thunar; do
                if [ -f "${'$'}_th" ] && head -1 "${'$'}_th" 2>/dev/null | grep -q '^#!'; then
                    rm -f "${'$'}_th" 2>/dev/null || true
                fi
            done
            for _th in /usr/bin/thunar /usr/bin/Thunar; do
                if [ -f "${'$'}_th" ] && head -1 "${'$'}_th" 2>/dev/null | grep -q '^#!'; then
                    if grep -q 'PocketLinux file-manager wrapper\|basename\|\.real' "${'$'}_th" 2>/dev/null; then
                        _real_ok=0
                        for _cand in /usr/bin/thunar.real /usr/bin/Thunar.real /usr/bin/Thunar /usr/bin/thunar; do
                            if [ -x "${'$'}_cand" ] && ! head -1 "${'$'}_cand" 2>/dev/null | grep -q '^#!'; then
                                _real_ok=1
                                break
                            fi
                        done
                        if [ "${'$'}_real_ok" != "1" ]; then
                            rm -f "${'$'}_th" 2>/dev/null || true
                        fi
                    fi
                fi
            done
            if [ -x /usr/bin/pcmanfm ] || [ -x /usr/bin/pcmanfm.real ]; then
                mkdir -p "${'$'}HOME/.local/share/applications" 2>/dev/null || true
                for _td in thunar Thunar; do
                    printf '%s\n' '[Desktop Entry]' 'Hidden=true' 'NoDisplay=true' \
                        > "${'$'}HOME/.local/share/applications/${'$'}_td.desktop" 2>/dev/null || true
                done
            fi
            # Prefer mini-session over startxfce4 (baked startCmd may still be startxfce4
            # if this container was set up before v13).
            if [ -x /usr/local/bin/pocketlinux-xfce-session ]; then
                case "$startCmd" in
                    startxfce4*|*/startxfce4*)
                        echo "Arch X11: rewriting startCmd startxfce4 → pocketlinux-xfce-session"
                        # shellcheck disable=SC2034
                        # Note: $startCmd is baked by Kotlin; override via re-exec below
                        __PL_ARCH_MINI=1
                        ;;
                esac
            fi
            # OOM guard: force software only when no HW backend was already selected.
            # Explicit Freedreno/Zink/Panfrost (from gpuEnv) must not be overwritten or
            # glmark2 always reports llvmpipe on Arch regardless of container settings.
            case "${'$'}{MESA_LOADER_DRIVER_OVERRIDE:-}" in
                kgsl|msm|zink|panfrost)
                    echo "Arch X11: keeping hardware GL (MESA_LOADER_DRIVER_OVERRIDE=${'$'}MESA_LOADER_DRIVER_OVERRIDE)"
                    ;;
                *)
                    export LIBGL_ALWAYS_SOFTWARE=1
                    export GALLIUM_DRIVER=llvmpipe
                    export MESA_LOADER_DRIVER_OVERRIDE=swrast
                    export MESA_DEBUG=silent
                    export GDK_GL=disable
                    echo "Arch X11: forced software GL + session restore cleared (OOM/black-screen guard)"
                    ;;
            esac
        fi
""".trimIndent()
