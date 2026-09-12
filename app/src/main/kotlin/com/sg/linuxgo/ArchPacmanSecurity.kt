package com.sg.linuxgo

import java.io.File

/**
 * Arch on PRoot: HTTPS only when the certificate matches the hostname,
 * plus signed packages. [SigLevel = Never] is a last-resort fallback
 * and must be visible to the user.
 *
 * `mirror.archlinuxarm.org` is ALARM's GeoIP balancer. Its TLS cert is
 * issued for `archlinuxarm.org` and does not include that hostname.
 * Official ALARM therefore publishes that name over HTTP only. We never
 * use it over HTTPS, and we do not disable TLS verification.
 */
object ArchPacmanSecurity {
    const val UNSIGNED_MARKER_REL = "etc/pocketlinux_pacman_unsigned"
    const val SIGNED_SIGLEVEL = "SigLevel = Required DatabaseOptional"
    const val LOCAL_SIGLEVEL = "LocalFileSigLevel = Optional"
    const val DEFAULT_MIRROR_BASE = "https://fl.us.mirror.archlinuxarm.org"
    /** ARMtix (Artix aarch64) package root. Not Arch Linux ARM. */
    const val ARTIX_DEFAULT_MIRROR_BASE = "https://armtix.artixlinux.org/repos"

    val WIZARD_MIRROR_BASES = listOf(
        DEFAULT_MIRROR_BASE,
        "https://ca.us.mirror.archlinuxarm.org",
        "https://de3.mirror.archlinuxarm.org",
        "https://mirrors.ustc.edu.cn/archlinuxarm",
        "https://mirror.nju.edu.cn/archlinuxarm",
    )

    private val sigNever = Regex("""(?im)^[ \t]*#?[ \t]*SigLevel[ \t]*=[ \t]*Never\b.*$""")
    private val localNever = Regex("""(?im)^[ \t]*#?[ \t]*LocalFileSigLevel[ \t]*=[ \t]*Never\b.*$""")
    private val serverLine = Regex(
        """^([ \t]*Server[ \t]*=[ \t]*)(https?://)([^/\s#]+)(\S*)(.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** Hosts whose HTTPS cert SAN matches (probed). */
    private val verifiedHttpsHosts = setOf(
        "fl.us.mirror.archlinuxarm.org",
        "ca.us.mirror.archlinuxarm.org",
        "de3.mirror.archlinuxarm.org",
        "mirrors.ustc.edu.cn",
        "mirror.nju.edu.cn",
    )

    /** NXDOMAIN / removed official names we previously shipped. */
    private val deadHosts = setOf(
        "il.us.mirror.archlinuxarm.org",
        "nz.mirror.archlinuxarm.org",
        "au.mirror.archlinuxarm.org",
    )

    /** HTTPS presents a cert for a different name. */
    private val httpsNameMismatchHosts = setOf(
        "mirror.archlinuxarm.org",
        "de.mirror.archlinuxarm.org",
        "de4.mirror.archlinuxarm.org",
        "dk.mirror.archlinuxarm.org",
        "tw2.mirror.archlinuxarm.org",
    )

    /** HTTPS handshake fails CA validation. */
    private val httpsUntrustedChainHosts = setOf(
        "nj.us.mirror.archlinuxarm.org",
        "fr.mirror.archlinuxarm.org",
    )

    fun signaturesDisabled(conf: String): Boolean {
        return sigNever.containsMatchIn(conf) || localNever.containsMatchIn(conf)
    }

    fun hasUnsignedMarker(rootfs: File): Boolean {
        return File(rootfs, UNSIGNED_MARKER_REL).isFile
    }

    fun shouldWarnUser(rootfs: File): Boolean {
        if (hasUnsignedMarker(rootfs)) return true
        val conf = File(rootfs, "etc/pacman.conf")
        return conf.isFile && signaturesDisabled(conf.readText())
    }

    fun writeUnsignedMarker(rootfs: File) {
        val marker = File(rootfs, UNSIGNED_MARKER_REL)
        marker.parentFile?.mkdirs()
        marker.writeText("unsigned\n")
    }

    fun isBrokenGeoIpMirrorUrl(url: String): Boolean {
        return Regex("""https?://mirror\.archlinuxarm\.org(?:/|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.trim())
    }

    fun rewriteMirrorsHttps(
        mirrorlist: String,
        emptyFallback: String = defaultHttpsMirrorlist(),
    ): String {
        val out = StringBuilder()
        var serverCount = 0
        for (line in mirrorlist.lineSequence()) {
            val match = serverLine.matchEntire(line.trimEnd())
            if (match == null) {
                out.appendLine(line)
                continue
            }
            val host = match.groupValues[3].lowercase()
            if (shouldDropHost(host, match.groupValues[2])) continue
            val scheme = if (host in verifiedHttpsHosts) "https://" else match.groupValues[2]
            out.append(match.groupValues[1])
            out.append(scheme)
            out.append(match.groupValues[3])
            out.append(match.groupValues[4])
            out.appendLine(match.groupValues[5])
            serverCount++
        }
        if (serverCount == 0) return emptyFallback
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd() + "\n"
    }

    fun defaultHttpsMirrorlist(): String {
        return """
            Server = https://fl.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://ca.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://de3.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://mirrors.ustc.edu.cn/archlinuxarm/${'$'}arch/${'$'}repo
            Server = https://mirror.nju.edu.cn/archlinuxarm/${'$'}arch/${'$'}repo
        """.trimIndent() + "\n"
    }

    /**
     * ARMtix uses `$repo/os/$arch`. Arch Linux ARM `$arch/$repo` 404s for `system.db`.
     */
    fun defaultArtixHttpsMirrorlist(): String {
        return """
            Server = https://armtix.artixlinux.org/repos/${'$'}repo/os/${'$'}arch
            Server = https://repo.xdan.eu/pacman/armtix/${'$'}repo/os/${'$'}arch
            Server = https://repo.armtixlinux.org/${'$'}repo/os/${'$'}arch
        """.trimIndent() + "\n"
    }

    fun looksArtixRootfs(rootfs: File): Boolean {
        if (File(rootfs, "etc/artix-release").isFile) return true
        val os = File(rootfs, "etc/os-release")
        if (os.isFile) {
            try {
                if (Regex("""(?im)^ID\s*=\s*"?artix"?\s*$""").containsMatchIn(os.readText())) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        val conf = File(rootfs, "etc/pacman.conf")
        if (conf.isFile) {
            try {
                val text = conf.readText()
                if (Regex("""(?m)^\[system\]""").containsMatchIn(text) &&
                    Regex("""(?m)^\[world\]""").containsMatchIn(text)
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun isAlarmMirrorlist(mirrorlist: String): Boolean {
        return mirrorlist.contains("archlinuxarm", ignoreCase = true)
    }

    fun looksLikeArmtixMirrorlist(mirrorlist: String): Boolean {
        val t = mirrorlist.lowercase()
        return t.contains("armtix") || t.contains("repo.xdan.eu")
    }

    /**
     * Repair an existing Arch rootfs mirrorlist. No-op on non-Arch trees.
     * Artix/ARMtix is never rewritten to Arch Linux ARM.
     * @return true if the file was written
     */
    fun applySecureMirrorlist(rootfs: File): Boolean {
        val mirror = File(rootfs, "etc/pacman.d/mirrorlist")
        val artix = looksArtixRootfs(rootfs)
        val looksArch = File(rootfs, "etc/arch-release").isFile ||
            File(rootfs, "usr/bin/pacman").isFile ||
            File(rootfs, "bin/pacman").isFile ||
            File(rootfs, "etc/pacman.conf").isFile
        if (!mirror.isFile && !looksArch && !artix) return false
        return try {
            val raw = if (mirror.isFile) mirror.readText() else ""
            val next = if (artix) {
                if (raw.isBlank() || isAlarmMirrorlist(raw) || !looksLikeArmtixMirrorlist(raw)) {
                    defaultArtixHttpsMirrorlist()
                } else {
                    rewriteMirrorsHttps(raw, defaultArtixHttpsMirrorlist())
                }
            } else {
                rewriteMirrorsHttps(raw)
            }
            if (next == raw) return false
            mirror.parentFile?.mkdirs()
            mirror.writeText(next)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Leave an existing Never config alone (already-installed environments).
     * Otherwise prefer Required DatabaseOptional instead of inserting Never.
     */
    fun preferSignedConf(conf: String): Pair<String, Boolean> {
        if (signaturesDisabled(conf)) return conf to false
        var text = conf
        var changed = false
        val sigLine = Regex("""(?im)^[ \t]*#?[ \t]*SigLevel[ \t]*=.*$""")
        if (sigLine.containsMatchIn(text)) {
            val next = sigLine.replace(text, SIGNED_SIGLEVEL)
            if (next != text) {
                text = next
                changed = true
            }
        } else {
            text = if (text.contains("[options]")) {
                text.replace("[options]", "[options]\n$SIGNED_SIGLEVEL\n$LOCAL_SIGLEVEL")
            } else {
                "$SIGNED_SIGLEVEL\n$LOCAL_SIGLEVEL\n$text"
            }
            changed = true
        }
        val localLine = Regex("""(?im)^[ \t]*#?[ \t]*LocalFileSigLevel[ \t]*=.*$""")
        if (localLine.containsMatchIn(text)) {
            val next = localLine.replace(text, LOCAL_SIGLEVEL)
            if (next != text) {
                text = next
                changed = true
            }
        }
        return text to changed
    }

    private fun shouldDropHost(host: String, scheme: String): Boolean {
        if (host in deadHosts) return true
        if (host == "mirror.archlinuxarm.org") return true
        val https = scheme.equals("https://", ignoreCase = true)
        return https && (host in httpsNameMismatchHosts || host in httpsUntrustedChainHosts)
    }
}
