package com.sg.linuxgo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** Session identity, passwd/uid, metadata, username detect. */

fun ContainerRestoreEngine.applySessionIdentity(rootfs: File, username: String, onLog: (String) -> Unit = {}) {
    val user = username.trim().ifBlank { "PocketLinux" }
    if (user == "root") {
        // Still write a file so tools know intentionally-root
        try {
            File(rootfs, "etc/pocketlinux").mkdirs()
            File(rootfs, "etc/pocketlinux/username").writeText("root\n")
        } catch (_: Exception) {
        }
        // Remove any previous guest-as-uid0 alias so root is first again
        restorePasswdRootIdentity(rootfs)
        return
    }

    val homeGuest = "/home/$user"
    try {
        File(rootfs, "etc/pocketlinux").mkdirs()
        File(rootfs, "etc/pocketlinux/username").writeText("$user\n")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "write username file: ${e.message}")
    }

    // Critical for GUI: getpwuid(0) / whoami / \u must not say "root"
    // while euid stays 0 so apt/dpkg still work.
    installPasswdUid0Alias(rootfs, user, homeGuest, onLog)
    // Prefer files NSS so libnss_systemd cannot map uid 0 → "root" for Qt/LXQt.
    ensureNssFilesFirst(rootfs)

    val profileD = File(rootfs, "etc/profile.d").apply { mkdirs() }
    // Normal-Linux model: system-wide only (/etc/profile.d + /etc/bash.bashrc).
    // Never rewrite ~/.bashrc — starship / oh-my-bash / user themes install and
    // persist exactly like on a regular desktop. getpwuid(0) → guest via passwd
    // alias so bash \u works without PS1 hacks. PROMPT_COMMAND is left alone so
    // theme installers own it.
    File(profileD, "pocketlinux-identity.sh").writeText(buildPocketLinuxIdentityScript(user))
    // Sort last so we win over Alpine /etc/profile PS1='\h:\w\$ ' (shows localhost).
    File(profileD, "zz-pocketlinux-prompt.sh").writeText(buildPocketLinuxPromptScript(user))
    // Patch Alpine stock /etc/profile so even shells that skip profile.d still get a user prompt.
    patchAlpineStockProfilePrompt(rootfs, user)

    // Non-login interactive shells (xfce4-terminal default) do not read profile.d.
    // Debian/Ubuntu ~/.bashrc sources /etc/bash.bashrc; Arch has /etc/bash.bashrc
    // when bash is installed. One-shot system hook — never touch ~/.bashrc.
    ensureSystemBashrcSourcesIdentity(rootfs)

    // whoami wrapper — real binary returns root under proot -0
    val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
    File(localBin, "whoami").writeText(
        """
        #!/bin/sh
        if [ -n "${'$'}POCKETLINUX_USERNAME" ] && [ "${'$'}POCKETLINUX_USERNAME" != "root" ]; then
          echo "${'$'}POCKETLINUX_USERNAME"
          exit 0
        fi
        if [ -r /etc/pocketlinux/username ]; then
          u=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
          if [ -n "${'$'}u" ] && [ "${'$'}u" != "root" ]; then
            echo "${'$'}u"
            exit 0
          fi
        fi
        if [ -x /usr/bin/whoami ]; then exec /usr/bin/whoami "${'$'}@"; fi
        if [ -x /bin/whoami ]; then exec /bin/whoami "${'$'}@"; fi
        echo root
        """.trimIndent() + "\n"
    )
    File(localBin, "whoami").setExecutable(true, false)

    // id -un / id -u -n → guest name; everything else → real id
    File(localBin, "id").writeText(
        """
        #!/bin/sh
        __pl_name() {
          if [ -n "${'$'}POCKETLINUX_USERNAME" ] && [ "${'$'}POCKETLINUX_USERNAME" != "root" ]; then
            echo "${'$'}POCKETLINUX_USERNAME"; return 0
          fi
          if [ -r /etc/pocketlinux/username ]; then
            u=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
            if [ -n "${'$'}u" ] && [ "${'$'}u" != "root" ]; then echo "${'$'}u"; return 0; fi
          fi
          return 1
        }
        case "${'$'}1" in
          -un|-nu)
            if __pl_name; then exit 0; fi
            ;;
          -u)
            if [ "${'$'}2" = "-n" ] || [ "${'$'}2" = "--name" ]; then
              if __pl_name; then exit 0; fi
            fi
            ;;
          -n)
            if [ "${'$'}2" = "-u" ]; then
              if __pl_name; then exit 0; fi
            fi
            ;;
        esac
        if [ -x /usr/bin/id ]; then exec /usr/bin/id "${'$'}@"; fi
        if [ -x /bin/id ]; then exec /bin/id "${'$'}@"; fi
        exit 1
        """.trimIndent() + "\n"
    )
    File(localBin, "id").setExecutable(true, false)

    // Strip legacy PocketLinux managed blocks from ~/.bashrc so the file is a
    // normal user bashrc again (starship/oh-my install flow identical to desktop).
    // We no longer write anything into ~/.bashrc on session start.
    stripLegacyBashrcIdentityBlocks(rootfs, user)

    // bash --login prefers ~/.bash_profile over ~/.profile (Arch). Without a
    // bashrc chain there, app Terminal never loads user themes / PATH lines.
    // Only ensures login → bashrc like stock Debian/Arch skel — does not inject themes.
    ensureLoginProfileSourcesBashrc(rootfs, user)

    // Ensure home exists
    try {
        File(rootfs, "home/$user").mkdirs()
    } catch (_: Exception) {
    }

    // Alpine default hostname is "localhost". Prompt no longer uses \h, but
    // keep a friendlier /etc/hostname for tools that read the file.
    try {
        val hn = File(rootfs, "etc/hostname")
        val current = if (hn.isFile) hn.readText().trim() else ""
        if (current.isEmpty() || current.equals("localhost", ignoreCase = true)) {
            hn.parentFile?.mkdirs()
            hn.writeText("pocketlinux\n")
            val hosts = File(rootfs, "etc/hosts")
            if (hosts.isFile) {
                var text = hosts.readText()
                if (!text.contains("pocketlinux")) {
                    text = text.replace(
                        Regex("""(?m)^127\.0\.0\.1\s+localhost\b.*$"""),
                        "127.0.0.1\tlocalhost pocketlinux"
                    )
                    if (!text.contains("pocketlinux")) {
                        text = text.trimEnd() + "\n127.0.0.1\tpocketlinux\n"
                    }
                    hosts.writeText(text)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "hostname repair: ${e.message}")
    }

    onLog("✓ Session identity: $user ($homeGuest) [uid0 alias + prompt]")
    Log.i(ContainerRestoreEngine.TAG, "applySessionIdentity: user=$user rootfs=${rootfs.absolutePath}")
}

/**
 * Session identity exports for /etc/profile.d (USER/HOME/POCKETLINUX_USERNAME).
 * Prompt is handled separately by [buildPocketLinuxPromptScript] (zz-*.sh).
 */
internal fun buildPocketLinuxIdentityScript(username: String): String {
    val user = username.trim().ifBlank { "PocketLinux" }
    return """
        # PocketLinux session identity (system-wide — do not put this in ~/.bashrc)
        # proot -0 keeps real uid 0 for sudo/apt; passwd uid0 alias makes \u/whoami
        # show the guest name. This file only sets USER/HOME for the shell env.
        if [ -z "${'$'}POCKETLINUX_USERNAME" ] || [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
          if [ -r /etc/pocketlinux/username ]; then
            __pl_u=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
            if [ -n "${'$'}__pl_u" ] && [ "${'$'}__pl_u" != "root" ]; then
              POCKETLINUX_USERNAME="${'$'}__pl_u"
            fi
            unset __pl_u
          fi
        fi
        if [ -z "${'$'}POCKETLINUX_USERNAME" ] || [ "${'$'}POCKETLINUX_USERNAME" = "root" ]; then
          POCKETLINUX_USERNAME="$user"
        fi
        export POCKETLINUX_USERNAME
        if [ -n "${'$'}POCKETLINUX_USERNAME" ] && [ "${'$'}POCKETLINUX_USERNAME" != "root" ]; then
          export USER="${'$'}POCKETLINUX_USERNAME"
          export LOGNAME="${'$'}POCKETLINUX_USERNAME"
          export HOME="/home/${'$'}POCKETLINUX_USERNAME"
          if [ -d "${'$'}HOME" ] && { [ "${'$'}PWD" = "/root" ] || [ "${'$'}PWD" = "/" ]; }; then
            cd "${'$'}HOME" 2>/dev/null || true
          fi
        fi
        """.trimIndent() + "\n"
}

/**
 * Force guest username into PS1. Alpine stock prompt is hostname-only
 * (`\h:\w\$ ` → `localhost:~#`). Under proot, bash `\h` often stays localhost
 * even when /etc/hostname is rewritten — so never rely on `\h` for identity.
 *
 * Filename should sort last (zz-*) so it runs after Alpine /etc/profile.d peers.
 */
internal fun buildPocketLinuxPromptScript(username: String): String {
    val user = username.trim().ifBlank { "PocketLinux" }
    return """
        # PocketLinux prompt (zz — last profile.d): guest user, not hostname-only
        # Skip when starship / user themes own the prompt.
        if [ -z "${'$'}{STARSHIP_SHELL:-}" ] && [ -z "${'$'}{POCKETLINUX_KEEP_PS1:-}" ]; then
          __pl_u="${'$'}{POCKETLINUX_USERNAME:-}"
          if [ -z "${'$'}__pl_u" ] || [ "${'$'}__pl_u" = "root" ]; then
            if [ -r /etc/pocketlinux/username ]; then
              __pl_u=${'$'}(tr -d '[:space:]' < /etc/pocketlinux/username 2>/dev/null)
            fi
          fi
          if [ -z "${'$'}__pl_u" ] || [ "${'$'}__pl_u" = "root" ]; then
            __pl_u="${'$'}{USER:-}"
          fi
          if [ -z "${'$'}__pl_u" ] || [ "${'$'}__pl_u" = "root" ]; then
            __pl_u="$user"
          fi
          export USER="${'$'}__pl_u"
          export LOGNAME="${'$'}__pl_u"
          # Always put guest username first. Avoid \h (proot gethostname → localhost).
          PS1="${'$'}__pl_u:\\w\\${'$'} "
          export PS1
          unset __pl_u
        fi
        """.trimIndent() + "\n"
}

/**
 * Replace Alpine's hostname-only PS1 in /etc/profile with a username prompt.
 * Returns true if the file was modified.
 */
internal fun patchAlpineStockProfilePrompt(rootfs: File, username: String): Boolean {
    val user = username.trim().ifBlank { "PocketLinux" }
    val isAlpine = File(rootfs, "etc/alpine-release").isFile ||
        runCatching {
            File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText()
                ?.contains("alpine", ignoreCase = true) == true
        }.getOrDefault(false)
    if (!isAlpine) return false
    val profile = File(rootfs, "etc/profile")
    if (!profile.isFile) return false
    return try {
        val raw = profile.readText()
        // Stock Alpine: PS1='\h:\w\$ '  (and zsh %m hostname form)
        var next = raw
            .replace("PS1='\\h:\\w\\$ '", "PS1=\"$user:\\w\\$ \"")
            .replace("PS1=\"\\h:\\w\\$ \"", "PS1=\"$user:\\w\\$ \"")
            .replace("PS1='%m:%~%# '", "PS1='$user:%~%# '")
            .replace("PS1=\"%m:%~%# \"", "PS1=\"$user:%~%# \"")
        // Fallback branch uses ${HOSTNAME%%.*} which is also localhost under proot
        if (next.contains("PS1='\${HOSTNAME%%.*}:\$PWD'") ||
            next.contains("PS1=\"\${HOSTNAME%%.*}:\$PWD\"")
        ) {
            next = next
                .replace("PS1='\${HOSTNAME%%.*}:\$PWD'", "PS1=\"$user:\$PWD\"")
                .replace("PS1=\"\${HOSTNAME%%.*}:\$PWD\"", "PS1=\"$user:\$PWD\"")
        }
        // Broader: any remaining PS1 that is only \h:…
        if (next.contains("PS1='\\h:")) {
            next = next.replace(Regex("""PS1='\\h:[^']*'"""), "PS1=\"$user:\\w\\$ \"")
        }
        if (next != raw) {
            profile.writeText(next)
            Log.i(ContainerRestoreEngine.TAG, "patchAlpineStockProfilePrompt: PS1 → $user")
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "patchAlpineStockProfilePrompt: ${e.message}")
        false
    }
}

/**
 * Guest PATH bootstrap for login shells.
 *
 * Why: Terminal mode uses `bash --login` with a clean system PATH. Tools like
 * OpenCode install to `$HOME/.opencode/bin` and only append to `~/.bashrc`.
 * Desktop GUI terminals source bashrc (non-login) so they "just work"; app
 * Terminal would report `command not found` without user-local bins here.
 *
 * Also hooks PROMPT_COMMAND so dirs created mid-session (curl|bash installers)
 * appear on PATH after the next prompt without `source ~/.bashrc`.
 */
internal fun ContainerRestoreEngine.buildUserPathProfileScript(): String {
    // $ must be escaped as ${'$'} inside Kotlin raw/trimIndent for guest shell.
    // Login shells only (profile.d). No PROMPT_COMMAND — mid-session installs
    // need `source ~/.bashrc` or a new tab, same as a normal Linux desktop.
    return """
        # PocketLinux PATH (system-wide profile.d — not written into ~/.bashrc)
        # Clean base PATH once (no Android host leakage), then common user tool bins.
        __pl_base_path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        if [ -z "${'$'}{__PL_PATH_BASE_SET:-}" ]; then
          PATH="${'$'}__pl_base_path"
          export __PL_PATH_BASE_SET=1
        fi
        __pl_prepend_path() {
          __pl_d="${'$'}1"
          [ -n "${'$'}__pl_d" ] && [ -d "${'$'}__pl_d" ] || return 0
          case ":${'$'}{PATH}:" in
            *":${'$'}{__pl_d}:"*) ;;
            *) PATH="${'$'}__pl_d:${'$'}PATH" ;;
          esac
        }
        if [ -n "${'$'}HOME" ]; then
          __pl_prepend_path "${'$'}HOME/.local/bin"
          __pl_prepend_path "${'$'}HOME/bin"
          __pl_prepend_path "${'$'}HOME/.opencode/bin"
          __pl_prepend_path "${'$'}HOME/.cargo/bin"
          __pl_prepend_path "${'$'}HOME/.npm-global/bin"
          __pl_prepend_path "${'$'}HOME/.npm/bin"
          __pl_prepend_path "${'$'}HOME/go/bin"
          __pl_prepend_path "${'$'}HOME/.bun/bin"
          __pl_prepend_path "${'$'}HOME/.deno/bin"
          __pl_prepend_path "${'$'}HOME/.local/share/fnm"
        fi
        export PATH
        unset __pl_base_path
        """.trimIndent() + "\n"
}

/**
 * System bashrc hook so non-login GUI terminals get USER/HOME without editing
 * the user's ~/.bashrc (normal Linux: user file is entirely theirs).
 */
internal fun ContainerRestoreEngine.ensureSystemBashrcSourcesIdentity(rootfs: File) {
    val marker = "# PocketLinux: system identity (managed — not for ~/.bashrc)"
    val snippet = """
        $marker
        if [ -r /etc/profile.d/pocketlinux-path.sh ]; then
          . /etc/profile.d/pocketlinux-path.sh
        fi
        if [ -r /etc/profile.d/pocketlinux-identity.sh ]; then
          . /etc/profile.d/pocketlinux-identity.sh
        fi
        if [ -r /etc/profile.d/zz-pocketlinux-prompt.sh ]; then
          . /etc/profile.d/zz-pocketlinux-prompt.sh
        fi
        """.trimIndent()

    val candidates = listOf(
        File(rootfs, "etc/bash.bashrc"),
        File(rootfs, "etc/bashrc")
    )
    // Prefer the file that exists; if neither, create Debian-style bash.bashrc
    val target = candidates.firstOrNull { it.isFile } ?: File(rootfs, "etc/bash.bashrc")
    try {
        if (!com.sg.linuxgo.util.GuestShellFiles.ensureParentDir(target)) return
        val existing = com.sg.linuxgo.util.GuestShellFiles.readTextOrEmpty(target)
        if (existing.contains(marker) || existing.contains("pocketlinux-identity.sh")) {
            // Refresh managed system block only (not user home files)
            if (existing.contains(marker)) {
                val next = com.sg.linuxgo.util.GuestShellFiles.upsertManagedBlock(
                    existing = existing,
                    startMarker = marker,
                    endMarker = "# PocketLinux: system identity end",
                    blockBody = snippet + "\n# PocketLinux: system identity end\n",
                    prependIfMissing = false
                )
                if (next != existing) {
                    com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(target, next)
                }
            }
            return
        }
        val body = if (existing.isBlank()) {
            snippet + "\n# PocketLinux: system identity end\n"
        } else {
            existing.trimEnd() + "\n\n" + snippet + "\n# PocketLinux: system identity end\n"
        }
        com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(target, body)
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "system bashrc identity: ${e.message}")
    }
}

/** Remove old per-user managed identity footers/headers from ~/.bashrc files. */
internal fun ContainerRestoreEngine.stripLegacyBashrcIdentityBlocks(rootfs: File, user: String) {
    val startMarkers = listOf(
        "# PocketLinux identity (managed — do not remove)",
        "# PocketLinux identity (managed"
    )
    val endMarker = "# PocketLinux identity end"
    val bashrcs = mutableListOf<File>()
    File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { home ->
        bashrcs.add(File(home, ".bashrc"))
    }
    bashrcs.add(File(rootfs, "root/.bashrc"))
    bashrcs.add(File(rootfs, "home/$user/.bashrc"))
    bashrcs.distinctBy { it.absolutePath }.forEach { brc ->
        try {
            if (!brc.isFile) return@forEach
            var text = com.sg.linuxgo.util.GuestShellFiles.readTextOrEmpty(brc)
            var changed = false
            for (sm in startMarkers) {
                if (text.contains(sm)) {
                    val next = com.sg.linuxgo.util.GuestShellFiles.stripManagedBlock(text, sm, endMarker)
                    if (next != text) {
                        text = next
                        changed = true
                    }
                }
            }
            // Also drop one-off "export USER=" lines install scripts appended (identity is system-wide now)
            // Do NOT strip user-authored exports — only exact installer fingerprints if present.
            if (changed) {
                com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(brc, text)
                Log.i(ContainerRestoreEngine.TAG, "stripped legacy identity block from ${brc.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "strip bashrc ${brc.absolutePath}: ${e.message}")
        }
    }
}

/**
 * Ensure bash login shells source `~/.bashrc` (where users install themes).
 *
 * Bash --login reads the **first** of: `~/.bash_profile`, `~/.bash_login`, `~/.profile`.
 * Arch typically uses `.bash_profile`; Debian/Ubuntu often only `.profile`.
 * Older PocketLinux only patched `.profile`, so Arch login terminals never loaded
 * bashrc themes after restart.
 */
internal fun ContainerRestoreEngine.ensureLoginProfileSourcesBashrc(rootfs: File, user: String) {
    val startMarker = "# PocketLinux: source bashrc for login shells (managed)"
    val endMarker = "# PocketLinux: source bashrc end"
    val snippet = """
        $startMarker
        if [ -n "${'$'}BASH_VERSION" ]; then
          if [ -f "${'$'}HOME/.bashrc" ]; then
            . "${'$'}HOME/.bashrc"
          fi
        fi
        $endMarker
        """.trimIndent()

    val homes = mutableListOf<File>()
    File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { homes.add(it) }
    homes.add(File(rootfs, "root"))
    homes.add(File(rootfs, "home/$user"))

    homes.distinctBy { it.absolutePath }.forEach { home ->
        // Always ensure .bash_profile exists when missing — bash prefers it over
        // .profile, so a patched .profile alone is ignored on Arch/login shells.
        val ensured = linkedSetOf<File>()
        ensured.add(File(home, ".bash_profile"))
        if (File(home, ".profile").isFile) ensured.add(File(home, ".profile"))
        if (File(home, ".bash_login").isFile) ensured.add(File(home, ".bash_login"))

        ensured.forEach { profile ->
            try {
                if (!com.sg.linuxgo.util.GuestShellFiles.ensureParentDir(profile)) {
                    Log.w(ContainerRestoreEngine.TAG, "profile patch skipped (no parent): ${profile.absolutePath}")
                    return@forEach
                }
                val existing = com.sg.linuxgo.util.GuestShellFiles.readTextOrEmpty(profile)
                // Already chains bashrc without our marker (Arch skel / Debian) — leave alone.
                if (!existing.contains(startMarker) && existing.contains("bashrc")) {
                    return@forEach
                }
                val next = com.sg.linuxgo.util.GuestShellFiles.upsertManagedBlock(
                    existing = existing,
                    startMarker = startMarker,
                    endMarker = endMarker,
                    blockBody = snippet,
                    prependIfMissing = false
                )
                if (next != existing &&
                    !com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(profile, next)
                ) {
                    Log.w(ContainerRestoreEngine.TAG, "profile patch write failed: ${profile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "profile patch ${profile.absolutePath}: ${e.message}")
            }
        }
    }
}



/**
 * Insert (or refresh) a passwd line so getpwuid(0) returns [user], not "root".
 *
 * glibc returns the **first** passwd entry matching the uid. Under proot -0,
 * euid is always 0, so without this XFCE/bash always show "root". Keeping a
 * real `root:x:0:0:…` line afterward preserves getpwnam("root"). The original
 * user:x:1000 entry is left for filesystem ownership consistency.
 *
 * Marker comment lets us re-apply cleanly on every GUI/terminal start.
 */
internal fun ContainerRestoreEngine.installPasswdUid0Alias(
    rootfs: File,
    user: String,
    homeGuest: String,
    onLog: (String) -> Unit = {}
) {
    val passwd = File(rootfs, "etc/passwd")
    if (!passwd.isFile) {
        onLog("⚠ /etc/passwd missing — cannot install uid0 identity alias")
        return
    }
    try {
        val marker = "# pocketlinux-uid0-alias"
        val aliasLine = "$user:x:0:0:$user:$homeGuest:/bin/bash"
        val raw = passwd.readText()
        // Drop previous managed alias block(s)
        val lines = raw.lines().toMutableList()
        val cleaned = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim() == marker || line.trim().startsWith("$marker ")) {
                // Skip marker + following alias line if it is a uid-0 alias for a user
                i++
                if (i < lines.size) {
                    val next = lines[i]
                    val parts = next.split(':')
                    if (parts.size >= 3 && parts[2] == "0" && parts[0] != "root") {
                        i++ // skip old alias
                        continue
                    }
                }
                continue
            }
            // Also strip any prior unmanaged "user:x:0:0:…" that is not root
            // only if name matches our session user (avoid deleting system accounts)
            val parts = line.split(':')
            if (parts.size >= 3 && parts[0] == user && parts[2] == "0") {
                i++
                continue
            }
            cleaned.add(line)
            i++
        }
        // Ensure a real root line exists
        val hasRoot = cleaned.any {
            val p = it.split(':')
            p.size >= 3 && p[0] == "root" && p[2] == "0"
        }
        if (!hasRoot) {
            cleaned.add(0, "root:x:0:0:root:/root:/bin/bash")
        }
        // Prepend alias so getpwuid(0) hits the guest name first
        val body = (listOf(marker, aliasLine) + cleaned)
            .joinToString("\n")
            .trimEnd() + "\n"
        passwd.writeText(body)

        // Optional: nss uses gshadow/group; map group 0 display name is usually fine as root.
        // Ensure shell is valid.
        onLog("✓ passwd uid0 alias: getpwuid(0) → $user (apt still root-capable)")
        Log.i(ContainerRestoreEngine.TAG, "installPasswdUid0Alias: $user → uid 0 first match")
    } catch (e: Exception) {
        Log.e(ContainerRestoreEngine.TAG, "installPasswdUid0Alias failed", e)
        onLog("! passwd identity alias failed: ${e.message}")
    }
}

/**
 * Force `passwd`/`group` to resolve via files first (or only).
 * On Debian, `passwd: files systemd` can make getpwuid(0) return root for
 * Qt/LXQt even when a uid0 alias exists earlier in /etc/passwd.
 */
internal fun ContainerRestoreEngine.ensureNssFilesFirst(rootfs: File) {
    val nss = File(rootfs, "etc/nsswitch.conf")
    if (!nss.isFile) return
    try {
        val text = nss.readText()
        val updated = text.lines().joinToString("\n") { line ->
            when {
                line.matches(Regex("""^passwd:\s*.*""")) -> "passwd:         files"
                line.matches(Regex("""^group:\s*.*""")) -> "group:          files"
                line.matches(Regex("""^shadow:\s*.*""")) -> "shadow:         files"
                line.matches(Regex("""^hosts:\s*.*""")) -> "hosts:          files dns"
                else -> line
            }
        }.trimEnd() + "\n"
        if (updated != text) {
            nss.writeText(updated)
            Log.i(ContainerRestoreEngine.TAG, "ensureNssFilesFirst: passwd/group → files")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureNssFilesFirst: ${e.message}")
    }
}

/** Remove managed uid0 guest aliases so getpwuid(0) is root again. */
internal fun ContainerRestoreEngine.restorePasswdRootIdentity(rootfs: File) {
    val passwd = File(rootfs, "etc/passwd")
    if (!passwd.isFile) return
    try {
        val marker = "# pocketlinux-uid0-alias"
        val lines = passwd.readLines()
        val cleaned = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim() == marker || line.trim().startsWith("$marker ")) {
                i++
                if (i < lines.size) {
                    val next = lines[i]
                    val parts = next.split(':')
                    if (parts.size >= 3 && parts[2] == "0" && parts[0] != "root") {
                        i++
                        continue
                    }
                }
                continue
            }
            cleaned.add(line)
            i++
        }
        passwd.writeText(cleaned.joinToString("\n").trimEnd() + "\n")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "restorePasswdRootIdentity: ${e.message}")
    }
}

/**
 * Look up uid:gid for [username] from guest /etc/passwd.
 * Prefers the non-zero (real) uid when a uid0 alias exists.
 */
fun ContainerRestoreEngine.detectGuestUidGid(rootfs: File, username: String): Pair<Int, Int>? {
    if (username.isBlank()) return null
    if (username == "root") return 0 to 0
    try {
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile) return 1000 to 1000
        // Prefer real (non-zero) uid when a uid0 display alias also exists
        var zeroMatch: Pair<Int, Int>? = null
        for (line in passwd.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(':')
            if (parts.size >= 4 && parts[0] == username) {
                val uid = parts[2].toIntOrNull() ?: continue
                val gid = parts[3].toIntOrNull() ?: continue
                if (uid != 0) return uid to gid
                zeroMatch = uid to gid
            }
        }
        if (zeroMatch != null) return zeroMatch
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "detectGuestUidGid: ${e.message}")
    }
    // Common default for first desktop user when passwd line is missing
    return if (username != "root") 1000 to 1000 else 0 to 0
}

/**
 * Detect the primary non-root guest username from a rootfs.
 *
 * Ground truth for who the desktop/terminal should present as:
 *  1. First real home under /home (prefer PocketLinux if present)
 *  2. Lowest UID ≥ 1000 in /etc/passwd (skip nobody)
 *
 * Returns null when only root (or no usable user) is present.
 */
fun ContainerRestoreEngine.detectGuestUsername(rootfs: File): String? {
    try {
        val home = File(rootfs, "home")
        if (home.isDirectory) {
            val homes = home.listFiles()
                ?.filter { it.isDirectory && it.name.isNotBlank() && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            homes.firstOrNull { it.name.equals("PocketLinux", ignoreCase = true) }
                ?.let { return it.name }
            homes.firstOrNull()?.let { return it.name }
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "detectGuestUsername /home scan: ${e.message}")
    }

    try {
        val passwd = File(rootfs, "etc/passwd")
        if (passwd.isFile) {
            val candidates = mutableListOf<Pair<Int, String>>()
            passwd.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val parts = line.split(':')
                if (parts.size < 6) return@forEachLine
                val name = parts[0]
                val uid = parts[2].toIntOrNull() ?: return@forEachLine
                if (uid >= 1000 && name != "nobody" && name != "nfsnobody" && !name.startsWith("nixbld")) {
                    candidates.add(uid to name)
                }
            }
            candidates.minByOrNull { it.first }?.let { return it.second }
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "detectGuestUsername /etc/passwd: ${e.message}")
    }
    return null
}

/**
 * Resolve the username that should own GUI/terminal sessions for [rootfs],
 * and persist it onto the container config when it differs.
 *
 * Priority: filesystem detection → embedded/current config (if non-root) → fallback.
 */
fun ContainerRestoreEngine.syncContainerUsername(
    rootfs: File,
    containerId: String,
    containerManager: ContainerManager,
    fallback: String = "PocketLinux",
    onLog: (String) -> Unit = {}
): String {
    val current = containerManager.getContainer(containerId)
    val detected = detectGuestUsername(rootfs)
    val configUser = current?.username?.trim().orEmpty()

    val resolved = when {
        !detected.isNullOrBlank() -> detected
        configUser.isNotBlank() && configUser != "root" -> configUser
        configUser.isNotBlank() -> configUser
        else -> fallback
    }

    if (current != null && current.username != resolved) {
        containerManager.updateContainer(current.copy(username = resolved))
        onLog("ℹ Guest user set to '$resolved' (config had '${current.username}')")
        Log.i(ContainerRestoreEngine.TAG, "syncContainerUsername $containerId: ${current.username} → $resolved (detected=$detected)")
    }
    // Always (re)install guest identity files so restore/install cannot leave root@ prompts.
    applySessionIdentity(rootfs, resolved, onLog)
    return resolved
}

/**
 * Apply .pocketlinux_config.json embedded in the backup (if present).
 * Returns true if metadata was found and applied (or safely ignored).
 *
 * After metadata, always re-sync username from the real rootfs so a stale
 * or missing "username" field cannot force root for GUI/terminal sessions.
 *
 * @param importGpuSettings When false (catalog / golden-image install), skip
 *   gpu_driver_mode and hw_accel from the tarball. Production installs download
 *   prebuilt distro+DE images from GitHub; those images often bake the bake-host
 *   GPU mode (zink/llvmpipe). Live GPU selection is device prefs + dri probe.
 *   User backup restore keeps importGpuSettings=true so their choice returns.
 */
fun ContainerRestoreEngine.applyEmbeddedMetadata(
    rootfs: File,
    containerId: String,
    containerManager: ContainerManager,
    importGpuSettings: Boolean = true,
    onLog: (String) -> Unit = {}
) {
    var meta = File(rootfs, ".pocketlinux_config.json")
    if (!meta.exists()) meta = File(rootfs, ".linuxgo_config.json")
    if (!meta.exists()) meta = File(rootfs, "etc/pocketlinux_config.json")
    if (!meta.exists()) meta = File(rootfs, "tmp/.pocketlinux_config.json")
    if (!meta.exists()) {
        // Still fix username from the extracted filesystem
        syncContainerUsername(rootfs, containerId, containerManager, onLog = onLog)
        return
    }

    try {
        val metadata = JSONObject(meta.readText())
        val configJson = metadata.optJSONObject("config")
        val settingsJson = metadata.optJSONObject("settings")

        if (configJson != null) {
            val restoredConfig = ContainerConfig.fromJson(configJson)
            val current = containerManager.getContainer(containerId)
            if (current != null) {
                // Keep wizard id / install flag; adopt image identity fields carefully.
                // Prefer a non-root username from either side so we never wipe a good user.
                val preferredUser = when {
                    restoredConfig.username.isNotBlank() && restoredConfig.username != "root" ->
                        restoredConfig.username
                    current.username.isNotBlank() && current.username != "root" ->
                        current.username
                    else -> restoredConfig.username.ifBlank { current.username }
                }
                containerManager.updateContainer(
                    restoredConfig.copy(
                        id = current.id,
                        isInstalled = true,
                        name = current.name.ifBlank { restoredConfig.name },
                        username = preferredUser
                    )
                )
            }
        }

        if (settingsJson != null && containerId.isNotEmpty()) {
            val prefsName = "container_${containerId}_settings"
            val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = settingsJson.get(key)
                when (key) {
                    "res_scale_pct" -> {
                        val intVal = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: 100
                            else -> 100
                        }
                        editor.putInt(key, intVal)
                    }
                    "res_scale", "vnc_quality", "vnc_compression" -> {
                        val floatVal = when (value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloatOrNull() ?: 0f
                            else -> 0f
                        }
                        editor.putFloat(key, floatVal)
                    }
                    "hw_accel" -> {
                        if (!importGpuSettings) {
                            onLog("Skipping image hw_accel (GPU mode is device-local for catalog installs)")
                            continue
                        }
                        val boolVal = when (value) {
                            is Boolean -> value
                            is String -> value.toBoolean()
                            is Number -> value.toInt() != 0
                            else -> false
                        }
                        editor.putBoolean(key, boolVal)
                    }
                    "big_screen_ready" -> {
                        val boolVal = when (value) {
                            is Boolean -> value
                            is String -> value.toBoolean()
                            is Number -> value.toInt() != 0
                            else -> false
                        }
                        editor.putBoolean(key, boolVal)
                    }
                    "gpu_driver_mode" -> {
                        if (!importGpuSettings) {
                            onLog("Skipping image gpu_driver_mode (use Auto / container settings)")
                            continue
                        }
                        editor.putString(key, value.toString())
                    }
                    "orientation", "gui_mode", "vnc_scaling", "vnc_password",
                    "setup_name" -> {
                        editor.putString(key, value.toString())
                    }
                    else -> {
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is Float -> editor.putFloat(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is String -> editor.putString(key, value)
                        }
                    }
                }
            }
            // commit() so setupDisplayConfig / setupGpuEnvConfig see prefs immediately
            // (catalog finalize and GUI prep run in the same flow).
            editor.commit()

            val bootstrap = Bootstrap(context)
            val restoredPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            if (restoredPrefs.getBoolean("hw_accel", false) && !bootstrap.isHardwareAccelSupported()) {
                onLog("⚠ HW Acceleration disabled: Not supported on this device.")
                restoredPrefs.edit().putBoolean("hw_accel", false).commit()
            }
        }

        meta.delete()
        onLog("✓ Container settings restored.")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "metadata apply failed: ${e.message}")
        onLog("! Failed to restore metadata: ${e.message}")
    }

    // Always reconcile username with real /home + /etc/passwd so GUI/terminal
    // never stay on "root" when the image has a normal user (e.g. PocketLinux).
    syncContainerUsername(rootfs, containerId, containerManager, onLog = onLog)
}

internal fun ContainerRestoreEngine.directorySizeBytes(dir: File): Long {
    var size = 0L
    val stack = java.util.ArrayDeque<File>()
    stack.push(dir)
    val rootPath = dir.absolutePath
    while (stack.isNotEmpty()) {
        val current = stack.pop()
        try {
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) continue
        } catch (_: Exception) {
        }

        val relPath = current.absolutePath.substringAfter(rootPath).trimStart('/')
        if (relPath.startsWith("tmp") ||
            relPath.startsWith("run") ||
            relPath.startsWith("proc") ||
            relPath.startsWith("sys") ||
            relPath.startsWith("dev") ||
            relPath.startsWith("var/run")
        ) {
            continue
        }

        val files = current.listFiles() ?: continue
        for (f in files) {
            try {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
            } catch (_: Exception) {
            }
            val fRel = f.absolutePath.substringAfter(rootPath).trimStart('/')
            if (fRel.startsWith("tmp") ||
                fRel.startsWith("run") ||
                fRel.startsWith("proc") ||
                fRel.startsWith("sys") ||
                fRel.startsWith("dev") ||
                fRel.startsWith("var/run")
            ) {
                continue
            }
            if (f.isDirectory) stack.push(f) else size += f.length()
        }
    }
    return size
}
