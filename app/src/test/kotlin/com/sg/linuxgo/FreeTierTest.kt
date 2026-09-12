package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTierTest {

    @Test
    fun alpineAndDebianAreFree() {
        assertTrue(FreeTier.isFreeDistro("alpine"))
        assertTrue(FreeTier.isFreeDistro("debian"))
        assertTrue(FreeTier.isFreeDistro("Alpine"))
        assertTrue(FreeTier.isFreeDistro("Debian"))
    }

    @Test
    fun allDistrosAreFreeInOpenSource() {
        assertTrue(FreeTier.isFreeDistro("alpine"))
        assertTrue(FreeTier.isFreeDistro("debian"))
        assertTrue(FreeTier.isFreeDistro("archlinux"))
        assertTrue(FreeTier.isFreeDistro("ubuntu"))
        assertTrue(FreeTier.isFreeDistro("fedora"))
    }

    @Test
    fun distroNeverRequiresPremiumInOpenSource() {
        assertFalse(FreeTier.distroRequiresPremium("debian", isPremiumUser = false))
        assertFalse(FreeTier.distroRequiresPremium("kali", isPremiumUser = false))
        assertFalse(FreeTier.distroRequiresPremium("ubuntu", isPremiumUser = false))
    }

    @Test
    fun allDesktopsAreFreeInOpenSource() {
        assertTrue(FreeTier.isFreeDesktop("mate"))
        assertTrue(FreeTier.isFreeDesktop("xfce4"))
        assertTrue(FreeTier.isFreeDesktop("lxqt"))
    }

    @Test
    fun desktopNeverRequiresPremiumInOpenSource() {
        assertFalse(FreeTier.desktopRequiresPremium("mate", isPremiumUser = false))
        assertFalse(FreeTier.desktopRequiresPremium("xfce4", isPremiumUser = false))
    }

    @Test
    fun experimentalNeverRequiresPremiumInOpenSource() {
        assertFalse(FreeTier.experimentalRequiresPremium(isPremiumUser = false))
        assertFalse(FreeTier.experimentalRequiresPremium(isPremiumUser = true))
    }

    @Test
    fun additionalEnvironmentNeverRequiresPremiumInOpenSource() {
        assertFalse(FreeTier.additionalEnvironmentRequiresPremium(0, false))
        assertFalse(FreeTier.additionalEnvironmentRequiresPremium(1, false))
        assertFalse(FreeTier.additionalEnvironmentRequiresPremium(2, false))
    }
}
