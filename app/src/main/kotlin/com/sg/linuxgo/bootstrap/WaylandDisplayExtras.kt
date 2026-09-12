package com.sg.linuxgo.bootstrap

import java.io.File

/**
 * Wayland-only guest config written by display setup (toml, wlroots helper, labwc).
 */
fun writeWaylandDisplayExtras(
    rootfs: File,
    username: String,
    scalePct: Int,
    usrLocalBin: File
) {
// Write /etc/pocketlinux/pocketlinux.toml and /etc/localdesktop/localdesktop.toml
val pocketlinuxDir = File(rootfs, "etc/pocketlinux")
if (!pocketlinuxDir.exists()) pocketlinuxDir.mkdirs()
val localdesktopDir = File(rootfs, "etc/localdesktop")
if (!localdesktopDir.exists()) localdesktopDir.mkdirs()

// Always launch as root inside proot (same model as X11: proot -0).
// pocketlinux-launch rewrites USER/HOME to the desktop identity.
// Using a non-root toml user triggers runuser, which breaks under Android
// NO_NEW_PRIVS and with dual PocketLinux passwd entries (uid0 alias + 1001).
val tomlContent = """
    [user]
    username = "root"

    [command]
    launch = "/usr/local/bin/pocketlinux-launch"
""".trimIndent()
File(pocketlinuxDir, "pocketlinux.toml").writeText(tomlContent)
File(localdesktopDir, "localdesktop.toml").writeText(tomlContent)

// Write /usr/local/bin/pocketlinux-wlroots-output
val wlrootsOutputScript = """
    #!/bin/sh
    # Keep labwc's wlroots output aligned with the Android host window.
    state_file="/tmp/localdesktop-output"
    lock_file="/tmp/pocketlinux-wlroots-output.pid"
    fallback_scale="${scalePct / 100f}"

    if [ -r "${'$'}lock_file" ]; then
        old_pid=${'$'}(cat "${'$'}lock_file" 2>/dev/null)
        if [ -n "${'$'}old_pid" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
            exit 0
        fi
    fi
    echo "${'$'}${'$'}" > "${'$'}lock_file"
    trap 'rm -f "${'$'}lock_file"' EXIT INT TERM

    first_output() {
        # Prefer head/cut over awk — Arch minimal images often lack gawk/mawk.
        # wlr-randr first line is typically: OUTPUT_NAME "Make Model"
        _line=${'$'}(wlr-randr 2>/dev/null | head -n 1)
        [ -z "${'$'}_line" ] && return 0
        case "${'$'}_line" in
            Modes:*|Current:*|Position:*|Transform:*|Scale:*) return 0 ;;
        esac
        echo "${'$'}_line" | cut -d' ' -f1
    }

    read_output_state() {
        target_mode=""
        target_scale="${'$'}fallback_scale"
        if [ -r "${'$'}state_file" ]; then
            . "${'$'}state_file"
            target_mode="${'$'}{LOCALDESKTOP_OUTPUT_MODE:-}"
            target_scale="${'$'}{LOCALDESKTOP_OUTPUT_SCALE:-${'$'}target_scale}"
        fi
        case "${'$'}target_mode" in
            *x*) ;;
            *) return 1 ;;
        esac
        case "${'$'}target_scale" in
            ''|*[!0-9]*) target_scale="${'$'}fallback_scale" ;;
        esac
    }

    apply_output() {
        output="${'$'}1"
        wlr-randr --output "${'$'}output" --custom-mode "${'$'}{target_mode}@60Hz" --scale "${'$'}target_scale" >/dev/null 2>&1 && return 0
        wlr-randr --output "${'$'}output" --custom-mode "${'$'}target_mode" --scale "${'$'}target_scale" >/dev/null 2>&1 && return 0
        wlr-randr --output "${'$'}output" --mode "${'$'}target_mode" --scale "${'$'}target_scale" >/dev/null 2>&1 && return 0
        wlr-randr --output "${'$'}output" --scale "${'$'}target_scale" >/dev/null 2>&1 && return 0
        return 1
    }

    last_config=""
    while true; do
        if ! read_output_state; then
            sleep 0.2
            continue
        fi
        output=${'$'}(first_output)
        if [ -n "${'$'}output" ]; then
            config="${'$'}output ${'$'}target_mode ${'$'}target_scale"
            if [ "${'$'}config" != "${'$'}last_config" ] && apply_output "${'$'}output"; then
                last_config="${'$'}config"
            fi
        fi
        sleep 1
    done
""".trimIndent()

val wlrootsOutputFile = File(usrLocalBin, "pocketlinux-wlroots-output")
wlrootsOutputFile.writeText(wlrootsOutputScript)
wlrootsOutputFile.setExecutable(true, false)

// Configure custom XFCE LabWC configs
val homeDir = if (username == "root") "root" else "home/$username"
val labwcDir = File(rootfs, "$homeDir/.config/xfce4/labwc")
if (!labwcDir.exists()) labwcDir.mkdirs()

File(labwcDir, "rc.xml").writeText("""
    <?xml version="1.0"?>
    <labwc_config>
      <core>
        <reuseOutputMode>yes</reuseOutputMode>
      </core>
    </labwc_config>
""".trimIndent())

val autostartFile = File(labwcDir, "autostart")
autostartFile.writeText(buildString {
appendLine("#!/bin/sh")
appendLine("/usr/local/bin/pocketlinux-wlroots-output >/tmp/pocketlinux-wlroots-output.log 2>&1 &")
appendLine()
appendLine("# Set cursor theme to Adwaita for XWayland visibility")
appendLine("xfconf-query -c xsettings -p /Gtk/CursorThemeName -n -t string -s \"Adwaita\" 2>/dev/null || true")
appendLine("xfconf-query -c xsettings -p /Gtk/CursorThemeSize -n -t int -s 12 2>/dev/null || true")
appendLine()
appendLine("# Helper function to apply wallpaper to all XFCE monitor workspaces in xfconf")
appendLine("set_xfce_wallpaper() {")
appendLine("    val=\"\$1\"")
appendLine("    if [ -z \"\$val\" ] || [ ! -f \"\$val\" ]; then")
appendLine("        return")
appendLine("    fi")
appendLine("    for prop in \$(xfconf-query -c xfce4-desktop -l 2>/dev/null); do")
appendLine("        if echo \"\$prop\" | grep -q \"last-image\"; then")
appendLine("            xfconf-query -c xfce4-desktop -p \"\$prop\" -s \"\$val\" 2>/dev/null || true")
appendLine("        fi")
appendLine("    done")
appendLine("    if command -v xrandr >/dev/null 2>&1; then")
appendLine("        for mon in \$(xrandr 2>/dev/null | awk '/ connected/ {print \$1}'); do")
appendLine("            for ws in 0 1 2 3; do")
appendLine("                xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/monitor\${mon}/workspace\${ws}/last-image\" -n -t string -s \"\$val\" 2>/dev/null || true")
appendLine("                xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/monitor\${mon}/workspace\${ws}/image-style\" -n -t int -s 5 2>/dev/null || true")
appendLine("            done")
appendLine("        done")
appendLine("    fi")
appendLine("    for mon in monitor0 monitorVirtual-0 monitorVirtual-1 monitorXWAYLAND0 monitorXWAYLAND1 monitorWL-1; do")
appendLine("        for ws in 0 1 2 3; do")
appendLine("            xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/\${mon}/workspace\${ws}/last-image\" -n -t string -s \"\$val\" 2>/dev/null || true")
appendLine("            xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/\${mon}/workspace\${ws}/image-style\" -n -t int -s 5 2>/dev/null || true")
appendLine("        done")
appendLine("    done")
appendLine("}")
appendLine()
appendLine("# Query XFCE wallpaper settings")
appendLine("XFCE_WALLPAPER=\"\"")
appendLine("if command -v xfconf-query >/dev/null 2>&1; then")
appendLine("    for prop in \$(xfconf-query -c xfce4-desktop -l 2>/dev/null); do")
appendLine("        if echo \"\$prop\" | grep -q \"last-image\"; then")
appendLine("            val=\$(xfconf-query -c xfce4-desktop -p \"\$prop\" 2>/dev/null)")
appendLine("            if [ -f \"\$val\" ]; then")
appendLine("                XFCE_WALLPAPER=\"\$val\"")
appendLine("            fi")
appendLine("        fi")
appendLine("    done")
appendLine("fi")
appendLine("if [ -z \"\$XFCE_WALLPAPER\" ]; then")
appendLine("    for f in /usr/share/xfce4/backdrops/pocketlinux_wp.png /usr/share/backgrounds/pocketlinux_wp.png /usr/share/backgrounds/xfce/pocketlinux_wp.png /usr/share/xfce4/backdrops/* /usr/share/backgrounds/pocketlinux-wallpaper.png /usr/share/backgrounds/xfce/xfce-blue.jpg /usr/share/backgrounds/xfce/*.jpg /usr/share/backgrounds/xfce/*.png /usr/share/backgrounds/*; do")
appendLine("        if [ -f \"\$f\" ]; then")
appendLine("            case \"\$f\" in")
appendLine("                *.svg) ;;")
appendLine("                *)")
appendLine("                    XFCE_WALLPAPER=\"\$f\"")
appendLine("                    break")
appendLine("                    ;;")
appendLine("            esac")
appendLine("        fi")
appendLine("    done")
appendLine("fi")
appendLine()
appendLine("# Apply wallpaper in the background to prevent blocking autostart during D-Bus init")
appendLine("(")
appendLine("    sleep 3")
appendLine("    if [ -n \"\$XFCE_WALLPAPER\" ]; then")
appendLine("        set_xfce_wallpaper \"\$XFCE_WALLPAPER\"")
appendLine("    fi")
appendLine(") >/dev/null 2>&1 &")
appendLine()
appendLine("# Run swaybg immediately as the wallpaper layer in Wayland mode (xfdesktop on XWayland cannot paint the Wayland background)")
appendLine("if command -v swaybg >/dev/null 2>&1; then")
appendLine("    if [ -n \"\$XFCE_WALLPAPER\" ]; then")
appendLine("        swaybg -i \"\$XFCE_WALLPAPER\" -m fill 2>/dev/null &")
appendLine("    else")
appendLine("        swaybg -c \"#000000\" 2>/dev/null &")
appendLine("    fi")
appendLine("fi")
appendLine()
appendLine("# Sync wallpaper / theme to GTK settings.ini. Do not rewrite icons on empty")
appendLine("# xfconf (D-Bus blips), and never restart xfce4-panel/xfsettingsd from this")
appendLine("# loop — that flipped icon packs and hitching WebGL.")
appendLine("(")
appendLine("    sleep 3")
appendLine("    LAST_WP=\"\$XFCE_WALLPAPER\"")
appendLine("    LAST_KEY=\"\"")
appendLine("    while true; do")
appendLine("        CURRENT_WP=\"\"")
appendLine("        if command -v xfconf-query >/dev/null 2>&1; then")
appendLine("            for prop in \$(xfconf-query -c xfce4-desktop -l 2>/dev/null); do")
appendLine("                if echo \"\$prop\" | grep -q \"last-image\"; then")
appendLine("                    val=\$(xfconf-query -c xfce4-desktop -p \"\$prop\" 2>/dev/null)")
appendLine("                    if [ -f \"\$val\" ] && [ \"\$val\" != \"\$LAST_WP\" ]; then")
appendLine("                        CURRENT_WP=\"\$val\"")
appendLine("                    fi")
appendLine("                fi")
appendLine("            done")
appendLine("        fi")
appendLine("        if [ -n \"\$CURRENT_WP\" ]; then")
appendLine("            set_xfce_wallpaper \"\$CURRENT_WP\"")
appendLine("            killall swaybg 2>/dev/null")
appendLine("            swaybg -i \"\$CURRENT_WP\" -m fill 2>/dev/null &")
appendLine("            LAST_WP=\"\$CURRENT_WP\"")
appendLine("        fi")
appendLine("        THEME=\$(xfconf-query -c xsettings -p /Net/ThemeName 2>/dev/null)")
appendLine("        ICON=\$(xfconf-query -c xsettings -p /Net/IconThemeName 2>/dev/null)")
appendLine("        CURSOR=\$(xfconf-query -c xsettings -p /Gtk/CursorThemeName 2>/dev/null)")
appendLine("        CURSOR_SIZE=\$(xfconf-query -c xsettings -p /Gtk/CursorThemeSize 2>/dev/null)")
appendLine("        if [ -z \"\$THEME\" ] && [ -z \"\$ICON\" ]; then")
appendLine("            sleep 2")
appendLine("            continue")
appendLine("        fi")
appendLine("        KEY=\"\$THEME|\$ICON|\$CURSOR|\$CURSOR_SIZE\"")
appendLine("        if [ \"\$KEY\" != \"\$LAST_KEY\" ]; then")
appendLine("            LAST_KEY=\"\$KEY\"")
appendLine("            mkdir -p \${HOME}/.config/glib-2.0/settings 2>/dev/null")
appendLine("            {")
appendLine("                echo \"[org/gnome/desktop/interface]\"")
appendLine("                [ -n \"\$THEME\" ] && echo \"gtk-theme='\$THEME'\"")
appendLine("                [ -n \"\$ICON\" ] && echo \"icon-theme='\$ICON'\"")
appendLine("                [ -n \"\$CURSOR\" ] && echo \"cursor-theme='\$CURSOR'\"")
appendLine("                [ -n \"\$CURSOR_SIZE\" ] && echo \"cursor-size=\$CURSOR_SIZE\"")
appendLine("            } > \${HOME}/.config/glib-2.0/settings/keyfile")
appendLine("            for gtk_ver in 3.0 4.0; do")
appendLine("                mkdir -p \${HOME}/.config/gtk-\${gtk_ver} 2>/dev/null")
appendLine("                {")
appendLine("                    echo \"[Settings]\"")
appendLine("                    [ -n \"\$THEME\" ] && echo \"gtk-theme-name=\$THEME\"")
appendLine("                    [ -n \"\$ICON\" ] && echo \"gtk-icon-theme-name=\$ICON\"")
appendLine("                    [ -n \"\$CURSOR\" ] && echo \"gtk-cursor-theme-name=\$CURSOR\"")
appendLine("                    [ -n \"\$CURSOR_SIZE\" ] && echo \"gtk-cursor-theme-size=\$CURSOR_SIZE\"")
appendLine("                    echo \"gtk-application-prefer-dark-theme=1\"")
appendLine("                } > \${HOME}/.config/gtk-\${gtk_ver}/settings.ini")
appendLine("            done")
appendLine("        fi")
appendLine("        sleep 2")
appendLine("    done")
appendLine(") >/tmp/theme-sync.log 2>&1 &")
})
autostartFile.setExecutable(true, false)

val labwcEnv = File(labwcDir, "environment")
labwcEnv.writeText(
    "XDG_SESSION_TYPE=wayland\n" +
        "XDG_CURRENT_DESKTOP=XFCE\n" +
        "XCURSOR_THEME=Adwaita\n" +
        "XCURSOR_SIZE=12\n" +
        "GSETTINGS_BACKEND=keyfile\n" +
        "PULSE_SERVER=tcp:127.0.0.1:14713\n"
)
File(labwcDir, "lock").writeText("")

// Clean up conflicts
File(rootfs, "$homeDir/.config/labwc/autostart").delete()
}
