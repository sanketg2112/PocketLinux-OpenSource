package com.sg.linuxgo

/**
 * Distro / desktop / feature gates for the new-environment picker.
 * This open-source build unlocks every published distro, desktop, and extra environment.
 */
object FreeTier {

    fun isFreeDistro(distroId: String): Boolean = true

    fun isFreeDesktop(desktopId: String): Boolean = true

    fun distroRequiresPremium(distroId: String, isPremiumUser: Boolean): Boolean = false

    fun desktopRequiresPremium(desktopId: String, isPremiumUser: Boolean): Boolean = false

    fun experimentalRequiresPremium(isPremiumUser: Boolean): Boolean = false

    fun additionalEnvironmentRequiresPremium(
        existingCount: Int,
        isPremiumUser: Boolean,
    ): Boolean = false
}
