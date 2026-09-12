package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the public container-image catalog (manifest.json) published by
 * PocketLinux_Releases on the OpenSource GitHub repo.
 *
 * The app only offers distro/desktop pairs that appear in this catalog
 * (unless legacy package install is unlocked).
 */
object ContainerImageCatalog {

    private const val TAG = "ContainerImageCatalog"

    /** Stable raw URL — update only if the public repo/branch changes. */
    const val DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/sanketg2112/PocketLinux-OpenSource/main/manifest.json"

    /**
     * GitHub Contents API (public). Bypasses raw.githubusercontent.com CDN lag
     * (branch tip can stay stale for ~5 minutes after a catalog push).
     */
    const val DEFAULT_MANIFEST_API_URL =
        "https://api.github.com/repos/sanketg2112/PocketLinux-OpenSource/contents/manifest.json?ref=main"

    data class Image(
        val id: String,
        val version: String,
        val tag: String,
        val filename: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val arch: String,
        val distro: String,
        val desktop: String,
        val notes: String = "",
        val encryptedUrl: String? = null,
        val encryptedSha256: String? = null,
        val encryptedSizeBytes: Long = 0L,
        val encryptedFilename: String? = null,
        val unencryptedFallback: Image? = null
    ) {
        /** App-side distro id (debian / alpine / …). */
        val normalizedDistro: String get() = normalizeDistro(distro)

        /** App-side DE id (xfce4 / lxqt / mate / …). */
        val normalizedDesktop: String get() = normalizeDesktop(desktop)

        /** True if an encrypted (.plbk) variant is available for this image. */
        val hasEncryptedVariant: Boolean
            get() = !encryptedUrl.isNullOrBlank()

        /**
         * Resolves this image for download.
         * The open-source build defaults to the unencrypted archive.
         * If [preferEncrypted] is true and an encrypted variant is available,
         * returns a copy with the encrypted archive's URL, filename, sha256, and size,
         * while keeping the unencrypted image as [unencryptedFallback].
         */
        fun resolveForDownload(preferEncrypted: Boolean = false): Image {
            if (preferEncrypted && hasEncryptedVariant) {
                return copy(
                    url = encryptedUrl!!,
                    sha256 = encryptedSha256.orEmpty(),
                    sizeBytes = if (encryptedSizeBytes > 0) encryptedSizeBytes else sizeBytes,
                    filename = encryptedFilename ?: encryptedUrl.substringAfterLast('/'),
                    unencryptedFallback = this
                )
            }
            return this
        }
    }

    data class Manifest(
        val schema: Int,
        val latest: String?,
        val images: List<Image>,
        val manifestUrl: String?,
        /**
         * Distro ids operators hid in the Releases dashboard App tab.
         * Production catalogs may already omit those images; this is defense in depth.
         */
        val hiddenDistros: Set<String> = emptySet(),
        /**
         * Distro+desktop combos hidden in the dashboard (e.g. "debian/mate").
         * Desktop segment uses catalog form (xfce, mate, lxqt) — not app xfce4.
         * Public catalogs may already omit those images; this is defense in depth.
         */
        val hiddenCombos: Set<String> = emptySet()
    )

    /** Map catalog / user labels → app distro ids. */
    fun normalizeDistro(raw: String): String {
        val d = raw.trim().lowercase()
        return when {
            d.isBlank() -> ""
            d.contains("kali") -> "kali"
            d.contains("debian") -> "debian"
            d.contains("ubuntu") -> "ubuntu"
            d.contains("alpine") -> "alpine"
            d.contains("fedora") -> "fedora"
            d.contains("void") -> "void"
            d.contains("suse") -> "opensuse"
            d.contains("artix") -> "artix"
            d.contains("arch") -> "archlinux"
            else -> d
        }
    }

    /**
     * Map catalog desktop labels → app DE ids used in [ContainerConfig.de].
     * Catalog may say "xfce"; app uses "xfce4".
     */
    fun normalizeDesktop(raw: String): String {
        val d = raw.trim().lowercase()
        return when {
            d.isBlank() -> ""
            d == "xfce" || d == "xfce4" || d.contains("xfce") -> "xfce4"
            d == "lxqt" || d.contains("lxqt") -> "lxqt"
            d == "mate" || d.contains("mate") -> "mate"
            d == "kde" || d.contains("plasma") || d.contains("kde") -> "kde"
            d == "ubuntu-de" || d == "ubuntude" || d == "ubuntu de" -> "ubuntu-de"
            d == "hyprland" || d.contains("hypr") -> "hyprland"
            d == "none" || d == "cli" || d == "headless" -> "none"
            else -> d
        }
    }

    /**
     * Fetch the catalog. Prefer the Contents API (always fresh after push),
     * then fall back to raw.githubusercontent.com with a cache-buster.
     */
    fun fetch(manifestUrl: String = DEFAULT_MANIFEST_URL): Manifest {
        // Custom URL (tests / overrides): hit that only
        if (manifestUrl != DEFAULT_MANIFEST_URL) {
            return fetchUrl(manifestUrl, accept = "application/json")
        }

        val errors = mutableListOf<String>()

        // 1) API — no CDN lag for main branch tip
        try {
            return fetchUrl(
                DEFAULT_MANIFEST_API_URL,
                accept = "application/vnd.github.raw"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Catalog API fetch failed, trying raw URL", e)
            errors.add("api: ${e.message}")
        }

        // 2) raw CDN — may lag ~5 min after push; bust query anyway
        val bustUrl = if (manifestUrl.contains('?')) {
            "$manifestUrl&_=${System.currentTimeMillis()}"
        } else {
            "$manifestUrl?_=${System.currentTimeMillis()}"
        }
        try {
            return fetchUrl(bustUrl, accept = "application/json")
        } catch (e: Exception) {
            errors.add("raw: ${e.message}")
            throw Exception("Could not fetch container catalog (${errors.joinToString("; ")})")
        }
    }

    private fun fetchUrl(url: String, accept: String): Manifest {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PocketLinux-Android")
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw Exception("Manifest HTTP $code: ${err.take(200)}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseImageObject(o: JSONObject): Image? {
        val rawUrl = o.optString("url", "")
        if (rawUrl.isBlank()) return null
        val url = if (rawUrl.startsWith("http://", ignoreCase = true)) {
            Log.w(TAG, "Upgrading insecure HTTP image URL to HTTPS: $rawUrl")
            "https://" + rawUrl.substring(7)
        } else {
            rawUrl
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "Ignoring image with non-HTTPS scheme: $url")
            return null
        }

        val encObj = o.optJSONObject("encrypted")
        val rawEncUrl = encObj?.optString("url", "")?.takeIf { it.isNotBlank() }
            ?: o.optString("encrypted_url", "").takeIf { it.isNotBlank() }
        val encUrl = rawEncUrl?.let {
            if (it.startsWith("http://", ignoreCase = true)) "https://" + it.substring(7) else it
        }
        val encSha256 = encObj?.optString("sha256", "")?.takeIf { it.isNotBlank() }
            ?: o.optString("encrypted_sha256", "").takeIf { it.isNotBlank() }
        val encSize = (encObj?.optLong("size_bytes", 0L) ?: 0L).let {
            if (it > 0) it else o.optLong("encrypted_size_bytes", 0L)
        }
        val encFilename = encObj?.optString("filename", "")?.takeIf { it.isNotBlank() }
            ?: o.optString("encrypted_filename", "").takeIf { it.isNotBlank() }
            ?: encUrl?.substringAfterLast('/')

        return Image(
            id = o.optString("id", "default"),
            version = o.optString("version", "0.0.0"),
            tag = o.optString("tag", "v" + o.optString("version", "0.0.0")),
            filename = o.optString("filename", url.substringAfterLast('/')),
            url = url,
            sha256 = o.optString("sha256", ""),
            sizeBytes = o.optLong("size_bytes", 0L),
            arch = o.optString("arch", "aarch64"),
            distro = o.optString("distro", ""),
            desktop = o.optString("desktop", ""),
            notes = o.optString("notes", ""),
            encryptedUrl = encUrl,
            encryptedSha256 = encSha256,
            encryptedSizeBytes = encSize,
            encryptedFilename = encFilename
        )
    }

    fun parse(jsonText: String): Manifest {
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("images")
        val images = mutableListOf<Image>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val img = parseImageObject(o) ?: continue
                images.add(img)
            }
        }
        val encArr = root.optJSONArray("encrypted_images")
        if (encArr != null) {
            for (i in 0 until encArr.length()) {
                val o = encArr.optJSONObject(i) ?: continue
                val img = parseImageObject(o) ?: continue
                images.add(img)
            }
        }
        val hidden = mutableSetOf<String>()
        val hiddenArr = root.optJSONArray("hidden_distros")
        if (hiddenArr != null) {
            for (i in 0 until hiddenArr.length()) {
                val id = normalizeDistro(hiddenArr.optString(i, ""))
                if (id.isNotBlank()) hidden.add(id)
            }
        }
        val hiddenCombos = mutableSetOf<String>()
        val comboArr = root.optJSONArray("hidden_combos")
        if (comboArr != null) {
            for (i in 0 until comboArr.length()) {
                val key = normalizeComboKey(comboArr.optString(i, ""))
                if (key.isNotBlank()) hiddenCombos.add(key)
            }
        }

        return Manifest(
            schema = root.optInt("schema", 1),
            latest = root.optString("latest", "").takeIf { it.isNotBlank() },
            images = images,
            manifestUrl = root.optString("manifest_url", "").takeIf { it.isNotBlank() },
            hiddenDistros = hidden,
            hiddenCombos = hiddenCombos
        )
    }

    /**
     * Catalog-side desktop id used in combo keys (xfce, not app xfce4).
     */
    fun catalogDesktopId(raw: String): String {
        val d = normalizeDesktop(raw)
        return when (d) {
            "xfce4" -> "xfce"
            else -> d
        }
    }

    /** Normalize "debian/mate" / "debian:mate" hide keys. */
    fun normalizeComboKey(raw: String): String {
        val s = raw.trim().lowercase().replace(':', '/')
        if ('/' !in s) return ""
        val left = s.substringBefore('/')
        val right = s.substringAfter('/')
        val d = normalizeDistro(left)
        val de = catalogDesktopId(right)
        if (d.isBlank() || de.isBlank()) return ""
        return "$d/$de"
    }

    fun comboKey(distroId: String, desktopId: String): String {
        val d = normalizeDistro(distroId)
        val de = catalogDesktopId(desktopId)
        if (d.isBlank() || de.isBlank()) return ""
        return "$d/$de"
    }

    fun matchesArch(img: Image, preferredArch: String = preferredArchFromDevice()): Boolean {
        if (preferredArch.isBlank() || img.arch.isBlank()) return true
        val a = preferredArch.lowercase()
        val b = img.arch.lowercase()
        return b == a ||
            (a.contains("arm64") && (b.contains("arm64") || b.contains("aarch64"))) ||
            (a.contains("aarch64") && (b.contains("arm64") || b.contains("aarch64")))
    }

    fun isDistroHidden(manifest: Manifest, distroId: String): Boolean {
        val want = normalizeDistro(distroId)
        if (want.isBlank()) return false
        return want in manifest.hiddenDistros
    }

    fun isComboHidden(manifest: Manifest, distroId: String, desktopId: String): Boolean {
        if (isDistroHidden(manifest, distroId)) return true
        val key = comboKey(distroId, desktopId)
        if (key.isBlank()) return false
        return key in manifest.hiddenCombos
    }

    /** Images that can run on this device arch and are not operator-hidden. */
    fun imagesForDevice(
        manifest: Manifest,
        preferredArch: String = preferredArchFromDevice()
    ): List<Image> = manifest.images.filter {
        matchesArch(it, preferredArch) &&
            !isDistroHidden(manifest, it.distro) &&
            !isComboHidden(manifest, it.distro, it.desktop)
    }

    /** Distinct app distro ids available in the catalog for this device. */
    fun availableDistroIds(
        manifest: Manifest,
        preferredArch: String = preferredArchFromDevice()
    ): List<String> {
        return imagesForDevice(manifest, preferredArch)
            .map { it.normalizedDistro }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { orderDistro(it) }
    }

    /** Distinct app DE ids for a distro in the catalog. */
    fun availableDesktopIds(
        manifest: Manifest,
        distroId: String,
        preferredArch: String = preferredArchFromDevice()
    ): List<String> {
        val want = normalizeDistro(distroId)
        return imagesForDevice(manifest, preferredArch)
            .filter { it.normalizedDistro == want || want.isBlank() }
            .map { it.normalizedDesktop }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { orderDesktop(it) }
    }

    private fun orderDistro(id: String): Int = when (id) {
        "debian" -> 0
        "ubuntu" -> 1
        "fedora" -> 2
        "kali" -> 3
        "alpine" -> 4
        "archlinux" -> 5
        else -> 9
    }

    private fun orderDesktop(id: String): Int = when (id) {
        "xfce4" -> 0
        "lxqt" -> 1
        "mate" -> 2
        "kde" -> 3
        "ubuntu-de" -> 4
        else -> 9
    }

    /**
     * Prefer exact distro + desktop match, then same-distro, then first arch-compatible image.
     *
     * Version selection is **always per distro (+ desktop)**: among images that match the
     * chosen combo, pick the highest semver. The catalog-wide [Manifest.latest] field is
     * never used to pin an older release when a newer one exists for that distro
     * (e.g. Debian 1.0.2 must win over Debian 1.0.0 even if `latest` is still `v1.0.0`).
     */
    fun selectImage(
        manifest: Manifest,
        preferredDistro: String? = null,
        preferredDesktop: String? = null,
        preferredArch: String = preferredArchFromDevice(),
        preferEncrypted: Boolean = false
    ): Image {
        if (manifest.images.isEmpty()) {
            throw Exception("Container image catalog is empty — publish a release first")
        }

        val wantDistro = preferredDistro?.let { normalizeDistro(it) }.orEmpty()
        val wantDesktop = preferredDesktop?.let { normalizeDesktop(it) }.orEmpty()

        val deviceImages = imagesForDevice(manifest, preferredArch).ifEmpty { manifest.images }

        fun versionRank(img: Image): Long {
            // "1.2.3" / "v1.2.3-foo" → comparable integer rank (major<<32 | minor<<16 | patch)
            val parts = img.version.removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .mapNotNull { it.toLongOrNull() }
            val major = parts.getOrElse(0) { 0L }
            val minor = parts.getOrElse(1) { 0L }
            val patch = parts.getOrElse(2) { 0L }
            return (major shl 32) or (minor shl 16) or patch
        }

        /** Highest semver among candidates (per-combo / per-score-group). */
        fun preferNewest(candidates: List<Image>): Image? {
            if (candidates.isEmpty()) return null
            return candidates.maxWithOrNull(
                compareBy<Image> { versionRank(it) }
                    .thenBy { it.tag }
            )
        }

        fun score(img: Image): Int {
            var s = 0
            if (wantDistro.isNotBlank() && img.normalizedDistro == wantDistro) s += 100
            if (wantDesktop.isNotBlank() && img.normalizedDesktop == wantDesktop) s += 50
            if (matchesArch(img, preferredArch)) s += 5
            return s
        }

        // Require a real match when the user picked a specific combo from the UI
        if (wantDistro.isNotBlank() && wantDesktop.isNotBlank()) {
            val exactMatches = deviceImages.filter {
                it.normalizedDistro == wantDistro && it.normalizedDesktop == wantDesktop
            }
            val exact = preferNewest(exactMatches)
            if (exact != null) {
                val selected = if (preferEncrypted) exact.resolveForDownload(true) else exact
                Log.i(
                    TAG,
                    "Selected exact ${selected.tag} (${selected.normalizedDistro}/${selected.normalizedDesktop}) " +
                        "[file=${selected.filename}, encrypted=${selected.hasEncryptedVariant || selected.url.endsWith(".plbk")}] " +
                        "from ${exactMatches.size} matching image(s)"
                )
                return selected
            }
            throw Exception(
                "No published image for $wantDistro + $wantDesktop. " +
                    "Publish that combo from the Releases dashboard first."
            )
        }

        // Group by score, then prefer newest within the top score
        val bestScore = deviceImages.maxOfOrNull { score(it) } ?: 0
        val top = deviceImages.filter { score(it) == bestScore }
        val best = preferNewest(top) ?: manifest.images.first()
        val selected = if (preferEncrypted) best.resolveForDownload(true) else best

        Log.i(
            TAG,
            "Selected image ${selected.tag} (${selected.normalizedDistro}/${selected.normalizedDesktop}) " +
                "[file=${selected.filename}, encrypted=${selected.hasEncryptedVariant || selected.url.endsWith(".plbk")}]"
        )
        return selected
    }

    fun preferredArchFromDevice(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("armeabi") || abi.contains("arm") -> "arm"
            else -> "aarch64"
        }
    }
}
