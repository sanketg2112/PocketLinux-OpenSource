package com.sg.linuxgo.ui.sheets

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.graphics.Color
import com.sg.linuxgo.R
import com.sg.linuxgo.ContainerImageCatalog
import com.sg.linuxgo.ui.theme.*

internal data class DistroOption(
    val id: String,
    val name: String,
    val desc: String,
    val iconRes: Int,
    val color: Color
)

internal data class DEOption(
    val id: String,
    val name: String,
    val desc: String,
    val iconRes: Int,
    val color: Color
)

/**
 * Canonical distro order for the new-environment picker:
 * Alpine | Debian first, then other published distros.
 * Fedora is a published catalog distro (GitHub golden image).
 */
internal val distros = listOf(
    DistroOption("alpine", "Alpine", "Lightweight", R.drawable.ic_alpine, DistroAlpine),
    DistroOption("debian", "Debian", "Stable • Large", R.drawable.ic_debian, DistroDebian),
    DistroOption("ubuntu", "Ubuntu", "User-friendly", R.drawable.ic_ubuntu, DistroUbuntu),
    DistroOption("fedora", "Fedora", "dnf • Workstation", R.drawable.ic_fedora, DistroFedora),
    DistroOption("archlinux", "Arch Linux", "Rolling • experimental", R.drawable.ic_archlinux, DistroArch),
    DistroOption("kali", "Kali Linux", "Rolling • Security", R.drawable.ic_kali, DistroKali),
)

/**
 * Distros that are installable only via live package bootstrap (legacy method).
 * Catalog / prebuilt still treats unpublished ids as coming-soon until golden images exist.
 */
internal val legacyPackageInstallDistros: List<DistroOption>
    get() = knownPickerDistros

internal fun distroVisibleOnInstallPicker(distroId: String): Boolean = distroId != "omarchy"

/** Published picker cards plus preview (coming-soon) cards. */
internal fun pickerDistros(): List<DistroOption> =
    (distros + comingSoonDistros).filter { distroVisibleOnInstallPicker(it.id) }

/** Published picker cards plus preview (coming-soon) cards. */
internal val knownPickerDistros: List<DistroOption>
    get() = pickerDistros()

/**
 * Coming-soon row for the catalog/prebuilt picker (not selectable there)
 * until a golden image for that id appears in the GitHub catalog.
 * Legacy package install uses [legacyPackageInstallDistros] so these become selectable.
 */
internal val comingSoonDistros = listOf(
    DistroOption("opensuse", "openSUSE", "zypper • Leap", R.drawable.ic_opensuse, DistroOpenSuse),
    DistroOption("void", "Void Linux", "xbps • Light", R.drawable.ic_void, DistroVoid),
    DistroOption("artix", "Artix", "Arch • no systemd", R.drawable.ic_artix, DistroArtix),
)

/**
 * Catalog / prebuilt picker: cards for distros that have a published image.
 * Includes ids that still live in [comingSoonDistros] once GitHub ships them,
 * so a catalog push does not stay stuck behind a hardcoded SOON badge.
 */
internal fun catalogInstallableDistros(
    publishedIds: Collection<String>,
): List<DistroOption> {
    val ids = publishedIds.toSet()
    return orderDistrosForPicker(pickerDistros().filter { it.id in ids })
}

/**
 * Preview cards that should show SOON in catalog mode — unpublished only.
 * A distro listed in [publishedIds] is never shown as coming soon.
 */
internal fun comingSoonCardsForCatalog(
    publishedIds: Collection<String>,
): List<DistroOption> {
    val ids = publishedIds.toSet()
    return comingSoonDistros.filter { distroVisibleOnInstallPicker(it.id) && it.id !in ids }
}

/** Free distro ids shown on the first row of the picker. */
internal val freeDistroPickerOrder = listOf("alpine", "debian")

/**
 * Alpine + Debian first (side by side in a 2-column grid), other distros after.
 * Missing Alpine/Debian ids are skipped; others keep relative [distros] order.
 */
internal fun orderDistrosForPicker(options: List<DistroOption>): List<DistroOption> {
    val (free, premium) = splitDistrosForPicker(options)
    return free + premium
}

/**
 * Alpine/Debian vs other lists for sectioned picker UI.
 */
internal fun splitDistrosForPicker(
    options: List<DistroOption>,
): Pair<List<DistroOption>, List<DistroOption>> {
    if (options.isEmpty()) return emptyList<DistroOption>() to emptyList()
    val byId = options.associateBy { it.id }
    val freeIds = freeDistroPickerOrder.toSet()
    val free = freeDistroPickerOrder.mapNotNull { byId[it] }
    val knownRest = knownPickerDistros.mapNotNull { byId[it.id] }.filter { it.id !in freeIds }
    val knownIds = freeIds + knownRest.map { it.id }.toSet()
    val unknownRest = options.filter { it.id !in knownIds }
    return free to (knownRest + unknownRest)
}

/**
 * Total physical RAM in megabytes via [ActivityManager.MemoryInfo.totalMem].
 * Returns 0 if the service is unavailable.
 */
internal fun deviceTotalRamMb(context: Context): Long {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
    } catch (_: Exception) {
        0L
    }
}

/**
 * Snap Android [totalMem] (often a few hundred MB under the OEM label) to a
 * marketed phone RAM class: 4, 6, 8, 12, or 16 GB.
 *
 * Midpoints between classes avoid odd labels like "11 GB" on a 12 GB phone.
 */
internal fun marketedRamGb(totalRamMb: Long): Long {
    if (totalRamMb <= 0L) return 0L
    val gb = totalRamMb / 1024.0
    return when {
        gb < 5.0 -> 4L
        gb < 7.0 -> 6L
        gb < 10.0 -> 8L
        gb < 14.0 -> 12L
        else -> 16L
    }
}

/**
 * Default distro from total device RAM (marketed class).
 *
 * - 4 GB and 6 GB phones → Alpine
 * - 8 GB and above → Debian
 * Unknown / 0 MB falls back to Alpine.
 */
internal fun recommendedDistroIdForTotalRamMb(totalRamMb: Long): String {
    val marketed = marketedRamGb(totalRamMb)
    return if (marketed in 1L..6L) "alpine" else if (marketed >= 8L) "debian" else "alpine"
}

/** Marketed RAM label for UI (e.g. "12"), or "unknown". */
internal fun approxRamGbLabel(totalRamMb: Long): String {
    val marketed = marketedRamGb(totalRamMb)
    return if (marketed <= 0L) "unknown" else marketed.toString()
}

private fun distroDisplayName(distroId: String): String {
    return knownPickerDistros.find { it.id == distroId }?.name
        ?: distroId.replaceFirstChar { it.uppercase() }
}

/**
 * Title / body / accent for the free-space recommendation card on step 1.
 * Names only the recommended distro (no RAM figure in the UI).
 */
internal fun distroRamRecommendationCopy(
    recommendedId: String,
): Triple<String, String, Color> {
    val name = distroDisplayName(recommendedId)
    val accent = when (recommendedId) {
        "alpine" -> DistroAlpine
        "debian" -> DistroDebian
        else -> Magenta
    }
    return Triple(
        "RECOMMENDED FOR YOUR PHONE",
        "$name is recommended for your phone and has been preselected.",
        accent,
    )
}

/**
 * Prefer [preferredId] when present in [availableIds]; otherwise first available, else [preferredId].
 */
internal fun pickDefaultDistroId(availableIds: List<String>, preferredId: String): String {
    if (availableIds.isEmpty()) return preferredId
    if (preferredId in availableIds) return preferredId
    // If preferred missing, still prefer the other recommended tier when present
    val fallbackRecommended = if (preferredId == "alpine") "debian" else "alpine"
    if (fallbackRecommended in availableIds) return fallbackRecommended
    return availableIds.first()
}

/**
 * Distro card corner badge.
 * RAM pick → RECOMMENDED; otherwise LIGHT / STABLE / HEAVY.
 */
internal fun distroCardBadgeLabel(
    distroId: String,
    recommendedId: String,
): String {
    if (distroId == recommendedId) return "RECOMMENDED"
    return when (distroId) {
        "alpine", "void" -> "LIGHT"
        "debian", "ubuntu", "fedora", "opensuse" -> "STABLE"
        "archlinux", "arch", "kali", "artix" -> "HEAVY"
        else -> "STABLE"
    }
}

/** Background color for [distroCardBadgeLabel]. */
internal fun distroCardBadgeColor(label: String): Color {
    return when (label) {
        "RECOMMENDED" -> Magenta
        "LIGHT" -> Color(0xFF00838F) // teal — lighter footprint
        "HEAVY" -> Color(0xFFE65100) // deep orange — heavier
        "STABLE" -> Color(0xFF2E7D32) // green
        "SOON" -> Color(0xFF616161) // muted grey — coming soon
        else -> Color(0xFF2E7D32)
    }
}

internal val desktops = listOf(
    DEOption("xfce4", "XFCE", "Fast & standard", R.drawable.ic_xfce, Color(0xFF2EB8E6)),
    DEOption("mate", "MATE", "Classic & stable", R.drawable.ic_mate, Color(0xFF6ABF6A)),
    DEOption("lxqt", "LXQt", "Lightweight Qt", R.drawable.ic_lxqt, Color(0xFF0078D4)),
    DEOption("kde", "KDE Plasma", "Feature-rich", R.drawable.ic_kde, Color(0xFF31a1ff)),
    DEOption("ubuntu-de", "Ubuntu DE", "Native Ubuntu", R.drawable.ic_ubuntu, Color(0xFFE95420)),
    DEOption("hyprland", "Hyprland", "Tiling Wayland", R.drawable.ic_hyprland, Color(0xFF58E1FF)),
)

internal val windowManagers = listOf(
    DEOption("openbox", "Openbox", "Minimal floating", R.drawable.ic_openbox, Color(0xFF888888)),
    DEOption("awesome", "Awesome", "Configurable", R.drawable.ic_awesome, Color(0xFFFFFFFF)),
    DEOption("i3", "i3WM", "Popular tiling", R.drawable.ic_i3, Color(0xFF4A9D9D)),
    DEOption("ubuntu-wm", "Ubuntu WM", "Native Ubuntu", R.drawable.ic_ubuntu, Color(0xFFE95420))
)

/** SharedPreferences key: when true, install uses live package bootstrap instead of golden image. */
const val PREF_LEGACY_PACKAGE_INSTALL = "legacy_package_install"

internal sealed class CatalogUiState {
    data object Loading : CatalogUiState()
    data class Ready(val manifest: ContainerImageCatalog.Manifest) : CatalogUiState()
    data class Error(val message: String) : CatalogUiState()
}
