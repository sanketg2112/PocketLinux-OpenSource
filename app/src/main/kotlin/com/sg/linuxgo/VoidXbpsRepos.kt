package com.sg.linuxgo

/**
 * Void Linux keeps a separate xbps repo per architecture.
 *
 * x86_64 glibc is `…/current`; aarch64 glibc is `…/current/aarch64`.
 * With `XBPS_ARCH=aarch64`, a repo of `…/current` fetches
 * `…/current/aarch64-repodata` (HTTP 404). The installer must write
 * `repository=…/current/aarch64` so xbps loads
 * `…/current/aarch64/aarch64-repodata`.
 *
 * musl aarch64 lives at `…/current/aarch64/musl`.
 */
object VoidXbpsRepos {
    const val OFFICIAL_HOST = "https://repo-default.voidlinux.org"
    const val DEFAULT_REPO = "$OFFICIAL_HOST/current/aarch64"

    fun resolveRepoUrl(
        mirror: String,
        arch: String = "aarch64",
        musl: Boolean = false,
    ): String {
        val base = mirror.trim().trimEnd('/')
        val archPath = if (musl) "$arch/musl" else arch
        if (base.isEmpty()) return "$OFFICIAL_HOST/current/$archPath"
        when {
            base.endsWith("/current/$archPath") -> return base
            base.endsWith("/current/$arch") -> return if (musl) "$base/musl" else base
            base.endsWith("/current") -> return "$base/$archPath"
            "/current/" in base -> return base
            else -> return "$base/current/$archPath"
        }
    }
}
