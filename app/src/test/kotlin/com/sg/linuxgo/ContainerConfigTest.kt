package com.sg.linuxgo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerConfigTest {

    @Test
    fun jsonRoundTripPreservesFields() {
        val original = ContainerConfig(
            id = "c1",
            distro = "debian",
            de = "xfce4",
            wm = "none",
            name = "My Debian",
            username = "user",
            software = listOf("firefox", "code"),
            createdAt = 1_700_000_000_000L,
            isInstalled = true,
            installRecommends = true,
            guiMode = "wayland"
        )
        val restored = ContainerConfig.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun fromJsonAppliesDefaultsForMissingKeys() {
        val json = JSONObject()
            .put("id", "legacy")
            .put("name", "Old")
        val cfg = ContainerConfig.fromJson(json)
        assertEquals("legacy", cfg.id)
        assertEquals("alpine", cfg.distro)
        assertEquals("xfce4", cfg.de)
        assertEquals("none", cfg.wm)
        assertEquals("PocketLinux", cfg.username)
        assertTrue(cfg.software.isEmpty())
        assertFalse(cfg.isInstalled)
        assertFalse(cfg.installRecommends)
        assertEquals("x11", cfg.guiMode)
    }

    @Test
    fun deLabelKnownIds() {
        assertEquals("XFCE", ContainerConfig.deLabel("xfce4"))
        assertEquals("XFCE", ContainerConfig.deLabel("xfce"))
        assertEquals("MATE", ContainerConfig.deLabel("mate"))
        assertEquals("LXQt", ContainerConfig.deLabel("lxqt"))
        assertEquals("No Desktop", ContainerConfig.deLabel("none"))
        assertEquals("KDE", ContainerConfig.deLabel("kde"))
        assertEquals("Hyprland", ContainerConfig.deLabel("hyprland"))
    }

    @Test
    fun distroLabelAndColorKnownIds() {
        assertEquals("Debian", ContainerConfig.distroLabel("debian"))
        assertEquals("Arch Linux", ContainerConfig.distroLabel("archlinux"))
        assertEquals("Alpine", ContainerConfig.distroLabel("alpine"))
        assertEquals("Ubuntu", ContainerConfig.distroLabel("ubuntu"))
        assertEquals("Kali Linux", ContainerConfig.distroLabel("kali"))
        assertEquals("Fedora", ContainerConfig.distroLabel("fedora"))
        assertEquals("Void Linux", ContainerConfig.distroLabel("void"))
        assertEquals("openSUSE", ContainerConfig.distroLabel("opensuse"))
        assertEquals("Artix", ContainerConfig.distroLabel("artix"))
        assertEquals(0xFFD63384.toInt(), ContainerConfig.distroColor("debian"))
        assertEquals(0xFF1793D1.toInt(), ContainerConfig.distroColor("archlinux"))
        assertEquals(0xFF2777FF.toInt(), ContainerConfig.distroColor("kali"))
        assertEquals(0xFF3C6EB4.toInt(), ContainerConfig.distroColor("fedora"))
        assertEquals(0xFF478061.toInt(), ContainerConfig.distroColor("void"))
        assertEquals(0xFF73BA25.toInt(), ContainerConfig.distroColor("opensuse"))
        assertEquals(0xFF10A0CC.toInt(), ContainerConfig.distroColor("artix"))
        assertEquals(0xFF00E5FF.toInt(), ContainerConfig.distroColor("unknown"))
    }
}
