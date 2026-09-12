package com.sg.linuxgo

import com.sg.linuxgo.ui.sheets.PREF_LEGACY_PACKAGE_INSTALL
import com.sg.linuxgo.ui.sheets.approxRamGbLabel
import com.sg.linuxgo.ui.sheets.desktops
import com.sg.linuxgo.ui.sheets.distroCardBadgeLabel
import com.sg.linuxgo.ui.sheets.distroRamRecommendationCopy
import com.sg.linuxgo.ui.sheets.catalogInstallableDistros
import com.sg.linuxgo.ui.sheets.comingSoonCardsForCatalog
import com.sg.linuxgo.ui.sheets.comingSoonDistros
import com.sg.linuxgo.ui.sheets.distroVisibleOnInstallPicker
import com.sg.linuxgo.ui.sheets.distros
import com.sg.linuxgo.ui.sheets.legacyPackageInstallDistros
import com.sg.linuxgo.ui.sheets.marketedRamGb
import com.sg.linuxgo.ui.sheets.orderDistrosForPicker
import com.sg.linuxgo.ui.sheets.pickDefaultDistroId
import com.sg.linuxgo.ui.sheets.recommendedDistroIdForTotalRamMb
import com.sg.linuxgo.ui.sheets.splitDistrosForPicker
import com.sg.linuxgo.ui.sheets.windowManagers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewContainerModelsTest {

    @Test
    fun distroOptionsCoverSupportedIds() {
        val ids = distros.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("debian", "kali", "alpine", "archlinux", "ubuntu", "fedora")))
        assertEquals(distros.size, distros.map { it.id }.toSet().size)
    }

    @Test
    fun distroPickerOrderIsAlpineDebianThenOthers() {
        // First row (2-col grid): Alpine | Debian; other distros below
        assertEquals(
            listOf("alpine", "debian", "ubuntu", "fedora", "archlinux", "kali"),
            distros.map { it.id },
        )
        val shuffled = listOf(
            distros.find { it.id == "kali" }!!,
            distros.find { it.id == "debian" }!!,
            distros.find { it.id == "ubuntu" }!!,
            distros.find { it.id == "fedora" }!!,
            distros.find { it.id == "alpine" }!!,
            distros.find { it.id == "archlinux" }!!,
        )
        assertEquals(
            listOf("alpine", "debian", "ubuntu", "fedora", "archlinux", "kali"),
            orderDistrosForPicker(shuffled).map { it.id },
        )
        // Only free available → still alpine then debian
        assertEquals(
            listOf("alpine", "debian"),
            orderDistrosForPicker(distros.filter { it.id in setOf("debian", "alpine") }).map { it.id },
        )
        val (firstRow, other) = splitDistrosForPicker(distros)
        assertEquals(listOf("alpine", "debian"), firstRow.map { it.id })
        assertEquals(listOf("ubuntu", "fedora", "archlinux", "kali"), other.map { it.id })
    }

    @Test
    fun comingSoonDistrosArePreviewOnlyAndNotInInstallList() {
        val soonIds = comingSoonDistros.map { it.id }
        assertEquals(
            listOf("opensuse", "void", "artix"),
            soonIds,
        )
        // Must not overlap installable picker ids.
        val installIds = distros.map { it.id }.toSet()
        soonIds.forEach { id ->
            assertFalse("coming soon id leaked into install list: $id", id in installIds)
        }
        assertTrue("fedora must be a published picker distro", "fedora" in installIds)
        assertFalse("fedora must not stay behind the SOON list", "fedora" in soonIds)
    }

    @Test
    fun legacyPackageInstallIncludesComingSoonDistros() {
        val legacyIds = legacyPackageInstallDistros.map { it.id }
        assertTrue(legacyIds.containsAll(listOf("alpine", "debian", "ubuntu", "fedora", "archlinux", "kali")))
        assertEquals(
            listOf("opensuse", "void", "artix"),
            comingSoonDistros.map { it.id },
        )
        assertTrue(legacyIds.containsAll(comingSoonDistros.map { it.id }))
        assertEquals(
            listOf(
                "alpine", "debian", "ubuntu", "fedora", "archlinux", "kali",
                "opensuse", "void", "artix",
            ),
            orderDistrosForPicker(legacyPackageInstallDistros).map { it.id },
        )
    }

    @Test
    fun catalogInstallableIncludesFedoraWhenPublished() {
        val published = listOf("alpine", "debian", "ubuntu", "archlinux", "kali", "fedora")
        assertEquals(
            listOf("alpine", "debian", "ubuntu", "fedora", "archlinux", "kali"),
            catalogInstallableDistros(published).map { it.id },
        )
        assertFalse(
            "published Fedora must not keep the SOON badge",
            comingSoonCardsForCatalog(published).any { it.id == "fedora" },
        )
        assertEquals(
            listOf("opensuse", "void", "artix"),
            comingSoonCardsForCatalog(published).map { it.id },
        )
    }

    @Test
    fun catalogPromotesPreviewDistroWhenGitHubPublishesIt() {
        val published = listOf("alpine", "debian", "void")
        assertEquals(
            listOf("alpine", "debian", "void"),
            catalogInstallableDistros(published).map { it.id },
        )
        assertFalse(comingSoonCardsForCatalog(published).any { it.id == "void" })
        assertEquals(listOf("opensuse", "artix"), comingSoonCardsForCatalog(published).map { it.id })
    }

    @Test
    fun unpublishedPreviewDistrosStaySoon() {
        val published = listOf("alpine", "debian", "ubuntu", "archlinux", "kali")
        assertFalse("fedora" in catalogInstallableDistros(published).map { it.id }.toSet())
        assertEquals(
            listOf("opensuse", "void", "artix"),
            comingSoonCardsForCatalog(published).map { it.id },
        )
    }

    @Test
    fun omarchyIsNeverOnInstallPicker() {
        assertFalse(distroVisibleOnInstallPicker("omarchy"))
        assertTrue(distroVisibleOnInstallPicker("debian"))
        assertTrue(distroVisibleOnInstallPicker("artix"))
        val published = listOf(
            "alpine", "debian", "ubuntu", "archlinux", "kali", "fedora", "omarchy",
        )
        assertFalse(catalogInstallableDistros(published).any { it.id == "omarchy" })
        assertFalse(comingSoonCardsForCatalog(emptyList()).any { it.id == "omarchy" })
        assertEquals(
            listOf("opensuse", "void", "artix"),
            comingSoonCardsForCatalog(published).map { it.id },
        )
    }

    @Test
    fun desktopAndWmOptionsHaveUniqueIds() {
        assertTrue(desktops.any { it.id == "xfce4" })
        assertTrue(desktops.any { it.id == "kde" })
        assertTrue(desktops.any { it.id == "hyprland" })
        assertTrue(windowManagers.any { it.id == "i3" })
        assertEquals(desktops.size, desktops.map { it.id }.toSet().size)
        assertEquals(windowManagers.size, windowManagers.map { it.id }.toSet().size)
    }

    @Test
    fun legacyPackageInstallPrefKeyStable() {
        assertEquals("legacy_package_install", PREF_LEGACY_PACKAGE_INSTALL)
    }

    @Test
    fun marketedRamSnapsToPhoneClasses() {
        assertEquals(0L, marketedRamGb(0L))
        // ~4 GB marketed
        assertEquals(4L, marketedRamGb(3_500L))
        assertEquals(4L, marketedRamGb(4_096L))
        // ~6 GB marketed
        assertEquals(6L, marketedRamGb(5_500L))
        assertEquals(6L, marketedRamGb(6_144L))
        // ~8 GB marketed
        assertEquals(8L, marketedRamGb(7_200L))
        assertEquals(8L, marketedRamGb(8_192L))
        // ~12 GB marketed (raw ~11 GiB must not show as 11)
        assertEquals(12L, marketedRamGb(11_000L))
        assertEquals(12L, marketedRamGb(11_500L))
        assertEquals(12L, marketedRamGb(12_288L))
        // ~16 GB marketed
        assertEquals(16L, marketedRamGb(15_000L))
        assertEquals(16L, marketedRamGb(16_384L))
    }

    @Test
    fun recommendedDistroIsAlpineOnFourAndSixGbPhones() {
        assertEquals("alpine", recommendedDistroIdForTotalRamMb(0L))
        assertEquals("alpine", recommendedDistroIdForTotalRamMb(3_500L))
        assertEquals("alpine", recommendedDistroIdForTotalRamMb(4_096L))
        assertEquals("alpine", recommendedDistroIdForTotalRamMb(5_500L))
        assertEquals("alpine", recommendedDistroIdForTotalRamMb(6_144L))
    }

    @Test
    fun recommendedDistroIsDebianOnEightGbAndAbove() {
        assertEquals("debian", recommendedDistroIdForTotalRamMb(7_200L))
        assertEquals("debian", recommendedDistroIdForTotalRamMb(8_192L))
        assertEquals("debian", recommendedDistroIdForTotalRamMb(11_000L))
        assertEquals("debian", recommendedDistroIdForTotalRamMb(12_288L))
        assertEquals("debian", recommendedDistroIdForTotalRamMb(16_000L))
    }

    @Test
    fun approxRamGbLabelUsesMarketedClassesOnly() {
        assertEquals("unknown", approxRamGbLabel(0L))
        assertEquals("4", approxRamGbLabel(4_096L))
        assertEquals("6", approxRamGbLabel(6_144L))
        assertEquals("8", approxRamGbLabel(8_000L))
        assertEquals("12", approxRamGbLabel(11_000L))
        assertEquals("16", approxRamGbLabel(15_500L))
    }

    @Test
    fun pickDefaultDistroPrefersRecommendedWhenPublished() {
        val ids = listOf("ubuntu", "debian", "alpine", "kali")
        assertEquals("alpine", pickDefaultDistroId(ids, "alpine"))
        assertEquals("debian", pickDefaultDistroId(ids, "debian"))
    }

    @Test
    fun pickDefaultDistroFallsBackToOtherRecommendedTier() {
        assertEquals("debian", pickDefaultDistroId(listOf("ubuntu", "debian"), "alpine"))
        assertEquals("alpine", pickDefaultDistroId(listOf("ubuntu", "alpine"), "debian"))
        assertEquals("ubuntu", pickDefaultDistroId(listOf("ubuntu", "kali"), "alpine"))
        assertEquals("alpine", pickDefaultDistroId(emptyList(), "alpine"))
    }

    @Test
    fun distroRamRecommendationCopyNamesOnlyRecommended() {
        val alpine = distroRamRecommendationCopy("alpine")
        assertEquals("RECOMMENDED FOR YOUR PHONE", alpine.first)
        assertEquals("Alpine is recommended for your phone and has been preselected.", alpine.second)
        assertTrue(!alpine.second.contains("Debian"))

        val debian = distroRamRecommendationCopy("debian")
        assertEquals("RECOMMENDED FOR YOUR PHONE", debian.first)
        assertEquals("Debian is recommended for your phone and has been preselected.", debian.second)
        assertTrue(!debian.second.contains("Alpine"))
    }

    @Test
    fun distroCardBadgesRecommendedAndFootprint() {
        assertEquals("RECOMMENDED", distroCardBadgeLabel("alpine", "alpine"))
        assertEquals("RECOMMENDED", distroCardBadgeLabel("debian", "debian"))
        assertEquals("LIGHT", distroCardBadgeLabel("alpine", "debian"))
        assertEquals("STABLE", distroCardBadgeLabel("debian", "alpine"))
        assertEquals("STABLE", distroCardBadgeLabel("ubuntu", "debian"))
        assertEquals("HEAVY", distroCardBadgeLabel("archlinux", "debian"))
        assertEquals("HEAVY", distroCardBadgeLabel("kali", "alpine"))
        assertEquals("LIGHT", distroCardBadgeLabel("void", "debian"))
        assertEquals("STABLE", distroCardBadgeLabel("fedora", "debian"))
        assertEquals("STABLE", distroCardBadgeLabel("opensuse", "alpine"))
        assertEquals("HEAVY", distroCardBadgeLabel("artix", "debian"))
        assertEquals("STABLE", distroCardBadgeLabel("unknown", "debian"))
    }
}
